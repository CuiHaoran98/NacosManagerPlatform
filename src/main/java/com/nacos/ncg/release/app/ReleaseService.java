package com.nacos.ncg.release.app;

import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.IdempotencyKey;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.PublishOutcome;
import com.nacos.ncg.adapter.nacos.port.RejectReason;
import com.nacos.ncg.adapter.nacos.port.Snapshot;
import com.nacos.ncg.adapter.nacos.port.Subscriber;
import com.nacos.ncg.adapter.nacos.port.SubscriberSnapshot;
import com.nacos.ncg.approval.domain.Approval;
import com.nacos.ncg.approval.repo.ApprovalRepository;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.domain.ChangeRequestState;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.notification.app.NotificationOutbox;
import com.nacos.ncg.release.domain.ReleaseOutcome;
import com.nacos.ncg.release.domain.ReleaseRecord;
import com.nacos.ncg.release.repo.ReleaseRecordRepository;
import com.nacos.ncg.shared.BaselineGuard;
import com.nacos.ncg.shared.ContentDigest;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.PermissionPolicy;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.crypto.CryptoService;
import com.nacos.ncg.shared.json.Jsonb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 发布编排（FR-04 / 流程 7.3~7.5 / C-ARC-03）——平台唯一写入口：
 * 事务 A：RELEASING + ReleaseRecord(WRITING) + 发布意图审计；
 * 事务外：adapter.publish（幂等键 release_key，写前 CAS 基线、写后读回比 MD5）；
 * 事务 B：发布结果 + 结果审计 + 通知 + 目录快照更新。
 * 不确定结果（REL-502-001）绝不自动重试，进人工对账 reconcile（流程 7.4）。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ChangeRequestRepository changes;
    private final ReleaseRecordRepository releases;
    private final ApprovalRepository approvals;
    private final CatalogService catalog;
    private final NacosConfigPort port;
    private final CryptoService crypto;
    private final AuditWriter auditWriter;
    private final NotificationOutbox outbox;
    private final TransactionTemplate tx;
    private final PermissionPolicy policy = new PermissionPolicy();
    private final BaselineGuard baselineGuard = new BaselineGuard();

    public ReleaseService(ChangeRequestRepository changes, ReleaseRecordRepository releases,
                          ApprovalRepository approvals, CatalogService catalog, NacosConfigPort port,
                          CryptoService crypto, AuditWriter auditWriter, NotificationOutbox outbox,
                          PlatformTransactionManager transactionManager) {
        this.changes = changes;
        this.releases = releases;
        this.approvals = approvals;
        this.catalog = catalog;
        this.port = port;
        this.crypto = crypto;
        this.auditWriter = auditWriter;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 发布结果（控制器按 outcome 映射 HTTP；VERIFIED=200、REJECTED=失败响应、INDETERMINATE=REL-502-001）。 */
    public record ReleaseResult(ReleaseRecord record, ChangeRequest change) { }

    /**
     * 发布（含预检）。status 必须 APPROVED|SCHEDULED（REL-409-001）；
     * 发布人≠审批人 或 发布人=应用负责人（REL-403-001）；
     * 基线漂移退 PENDING_APPROVAL（REL-412-001，流程 7.5）。
     */
    /** 灰度元数据（R-REL-03：灰度方式、条件、观察窗口随发布记录留痕）。 */
    public record GraySpec(String method, String condition, Integer observationWindowMinutes) {}

    public ReleaseResult release(String changeNo, String targetVersion,
                                 String publisherId, String traceId) {
        return release(changeNo, targetVersion, publisherId, null, traceId, "MANUAL", null);
    }

    public ReleaseResult release(String changeNo, String targetVersion,
                                 String publisherId, GraySpec gray, String traceId) {
        return release(changeNo, targetVersion, publisherId, gray, traceId, "MANUAL", null);
    }

    /** sourceType：MANUAL|SCHEDULED|BATCH|ROLLBACK|RESTORE；systemActorId 非空=系统代执行（审计记双方）。 */
    public ReleaseResult release(String changeNo, String targetVersion,
                                 String publisherId, GraySpec gray, String traceId,
                                 String sourceType, String systemActorId) {
        // ---- 幂等：同 release_key 直接返回既有记录（R-REL-04）----
        String releaseKey = changeNo + ":" + targetVersion;
        Optional<ReleaseRecord> replayed = releases.findByReleaseKey(releaseKey);
        if (replayed.isPresent()) {
            ChangeRequest cr = changes.findById(changeNo).orElseThrow();
            return new ReleaseResult(replayed.get(), cr);
        }

        ChangeRequest cr = changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
        String status = cr.getStatus();
        if (!ChangeRequestState.APPROVED.name().equals(status)
                && !ChangeRequestState.SCHEDULED.name().equals(status)) {
            throw new NcgException(NcgErrorCode.REL_409_001,
                    "change not releasable in status " + status);
        }

        App app = catalog.getApp(cr.getAppId());
        String approverId = lastApprover(changeNo);
        if (!policy.canPublish(publisherId, approverId, app.getOwnerId())) {
            throw new NcgException(NcgErrorCode.REL_403_001,
                    "publisher must differ from approver, or be the app owner (R-APR-03)");
        }

        // ---- 预检（事务外，流程 7.3 步骤 2）----
        ConfigMapping mapping = catalog.getMapping(cr.getMappingId());
        Env env = catalog.getEnv(cr.getEnvId());
        ConfigRef ref = new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId());
        EnvId envId = new EnvId(env.getEnvCode());
        checkReleaseWindow(env);
        Snapshot current;
        try {
            current = port.read(envId, ref);
        } catch (NcgException e) {
            if (e.errorCode() != NcgErrorCode.SYS_404_001) {
                // 实例不可达：不写、不改状态（恢复后不自动补发），发布类失败码
                throw new NcgException(NcgErrorCode.REL_503_001,
                        "nacos instance unavailable for env " + env.getEnvCode() + ": " + e.getMessage());
            }
            // 配置不存在（SYS-404-001）：CREATE/RESTORE 的正常起点=无基线，放行走 CAS 创建；
            // 冻结过基线却消失=基线漂移（流程 7.5）。
            if (cr.getBaselineMd5() != null) {
                final String frozenGone = cr.getBaselineMd5();
                tx.executeWithoutResult(s -> {
                    cr.setStatus(ChangeRequestState.PENDING_APPROVAL.name());
                    cr.setUpdatedAt(Instant.now());
                    changes.save(cr);
                    outbox.enqueue(NotificationOutbox.EventType.CHANGE_SUBMITTED, "CHANGE", changeNo,
                            Map.of("changeNo", changeNo, "reason", "baseline drifted before release"), null);
                    auditWriter.append(new AuditWriter.Command(
                            ActorType.SYSTEM, "release-precheck", null, AuditAction.RELEASE_RESULT,
                            "CHANGE", changeNo, null, null, false,
                            NcgErrorCode.REL_412_001.code(), changeNo, traceId));
                });
                throw new NcgException(NcgErrorCode.REL_412_001,
                        "baseline drifted (frozen=" + frozenGone + ", current=absent)");
            }
            current = null;
        }
        if (current != null) {
            BaselineGuard.Check check = baselineGuard.precheck(cr.getBaselineMd5(), current.md5());
            if (check == BaselineGuard.Check.DRIFTED || check == BaselineGuard.Check.NOT_FROZEN) {
                // 流程 7.5：退回审批，展示三方 Diff（基线/当前/期望）
                tx.executeWithoutResult(s -> {
                    cr.setStatus(ChangeRequestState.PENDING_APPROVAL.name());
                    cr.setUpdatedAt(Instant.now());
                    changes.save(cr);
                    outbox.enqueue(NotificationOutbox.EventType.CHANGE_SUBMITTED, "CHANGE", changeNo,
                            Map.of("changeNo", changeNo, "reason", "baseline drifted before release"), null);
                    auditWriter.append(new AuditWriter.Command(
                            ActorType.SYSTEM, "release-precheck", null, AuditAction.RELEASE_RESULT,
                            "CHANGE", changeNo, null, null, false,
                            NcgErrorCode.REL_412_001.code(), changeNo, traceId));
                });
                throw new NcgException(NcgErrorCode.REL_412_001,
                        "baseline drifted (frozen=" + cr.getBaselineMd5() + ", current=" + current.md5() + ")");
            }
        }

        String content = crypto.decrypt(cr.getContentCiphertext());
        String newMd5 = ContentDigest.md5Hex(content);
        Instant startedAt = Instant.now();

        // ---- 事务 A：状态 + 发布意图（先提交，不包外部调用）----
        ReleaseRecord record;
        try {
            record = tx.execute(statusTx -> {
            ChangeRequest r = changes.findById(changeNo).orElseThrow();
            r.setStatus(ChangeRequestState.RELEASING.name());
            r.setPublisherId(publisherId);
            r.setUpdatedAt(Instant.now());
            changes.save(r);

            ReleaseRecord rec = new ReleaseRecord();
            rec.setReleaseId(Ulid.generate(startedAt));
            rec.setReleaseKey(releaseKey);
            rec.setChangeNo(changeNo);
            rec.setMappingId(cr.getMappingId());
            rec.setEnvId(cr.getEnvId());
            rec.setSourceType(sourceType);
            rec.setSystemActorId(systemActorId);
            rec.setTargetVersion(targetVersion);
            rec.setOutcome(ReleaseOutcome.WRITING.name());
            rec.setExpectedMd5(newMd5);
            rec.setOperatorId(publisherId);
            rec.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
            rec.setStartedAt(startedAt);
            rec.setNacosReceipt(Jsonb.empty());
            rec = releases.save(rec);
            auditWriter.append(new AuditWriter.Command(
                    systemActorId != null ? ActorType.SYSTEM : ActorType.USER,
                    systemActorId != null ? systemActorId : publisherId,
                    null, AuditAction.RELEASE_INTENT,
                    "RELEASE", rec.getReleaseId(), null, null, true, null, changeNo, traceId));
            return rec;
            });
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 并发重复发布：uk_release_key 兜底，事务回滚后返回既有记录（REL-409-002 幂等命中）
            ReleaseRecord existing = releases.findByReleaseKey(releaseKey).orElseThrow(() -> dup);
            return new ReleaseResult(existing, changes.findById(changeNo).orElseThrow());
        }

        // ---- 事务外：写 Nacos（CAS 基线 + 写后读回；不自动重试）----
        PublishOutcome outcome;
        try {
            outcome = port.publish(envId, ref, content, cr.getBaselineMd5(),
                    new IdempotencyKey(changeNo, targetVersion));
        } catch (RuntimeException e) {
            // 适配器契约外异常：按不确定处理（写入可能已发出）
            outcome = new PublishOutcome.Indeterminate("adapter error: " + e.getMessage(), Map.of());
        }

        // ---- R-REL-07：订阅差异（发布后订阅实例与提交时影响面快照比对，差异进审计+告警日志，不静默忽略）----
        final Jsonb subscriberDiff = (outcome instanceof PublishOutcome.Verified)
                ? detectSubscriberDiff(envId, ref, cr) : Jsonb.empty();

        // ---- 事务 B：结果 + 审计 + 通知 ----
        final PublishOutcome finalOutcome = outcome;
        ReleaseRecord resultRecord = tx.execute(resultTx -> {
            ReleaseRecord rec = releases.findById(record.getReleaseId()).orElseThrow();
            ChangeRequest r = changes.findById(changeNo).orElseThrow();
            rec.setFinishedAt(Instant.now());
            if (finalOutcome instanceof PublishOutcome.Verified v) {
                rec.setOutcome(ReleaseOutcome.VERIFIED.name());
                rec.setReadbackMd5(v.readbackMd5());
                rec.setNacosReceipt(Jsonb.of(receiptJson(v.receipt(), gray)));
                r.setStatus(ChangeRequestState.SUCCEEDED.name());
                mapping.setLastSnapshotMd5(v.readbackMd5());
                mapping.setLastCollectedAt(Instant.now());
                catalog.updateSnapshot(mapping.getMappingId(), v.readbackMd5(), Instant.now());
            } else if (finalOutcome instanceof PublishOutcome.Rejected rej) {
                rec.setOutcome(ReleaseOutcome.REJECTED.name());
                rec.setRejectReason(rej.reason().name());
                rec.setNacosReceipt(Jsonb.of(receiptJson(rej.receipt(), gray)));
                rec.setErrorCode(rejectCode(rej.reason()).code());
                if (rej.reason() == RejectReason.BASELINE) {
                    // 写入时基线被并发改动：退回审批（流程 7.5）
                    r.setStatus(ChangeRequestState.PENDING_APPROVAL.name());
                    outbox.enqueue(NotificationOutbox.EventType.CHANGE_SUBMITTED, "CHANGE", changeNo,
                            Map.of("changeNo", changeNo, "reason", "baseline rejected at write (CAS)"), null);
                } else {
                    r.setStatus(ChangeRequestState.FAILED.name());
                    outbox.enqueue(NotificationOutbox.EventType.RELEASE_FAILED, "RELEASE", rec.getReleaseId(),
                            Map.of("changeNo", changeNo, "reason", rej.reason().name()), null);
                }
            } else {
                PublishOutcome.Indeterminate ind = (PublishOutcome.Indeterminate) finalOutcome;
                rec.setOutcome(ReleaseOutcome.INDETERMINATE.name());
                rec.setNacosReceipt(Jsonb.of(receiptJson(ind.receipt(), gray)));
                rec.setErrorCode(NcgErrorCode.REL_502_001.code());
                r.setStatus(ChangeRequestState.INDETERMINATE.name());
                outbox.enqueue(NotificationOutbox.EventType.RELEASE_INDETERMINATE, "RELEASE", rec.getReleaseId(),
                        Map.of("changeNo", changeNo, "reason", ind.detail()), null);
            }
            r.setUpdatedAt(Instant.now());
            changes.save(r);
            ReleaseRecord saved = releases.save(rec);
            boolean verified = ReleaseOutcome.VERIFIED.name().equals(saved.getOutcome());
            auditWriter.append(new AuditWriter.Command(
                    systemActorId != null ? ActorType.SYSTEM : ActorType.USER,
                    systemActorId != null ? systemActorId : publisherId,
                    null, AuditAction.RELEASE_RESULT,
                    "RELEASE", saved.getReleaseId(), null,
                    subscriberDiff.isEmpty() ? null : subscriberDiff, verified,
                    verified ? null : saved.getErrorCode(), changeNo, traceId));
            return saved;
        });

        return new ReleaseResult(resultRecord, changes.findById(changeNo).orElseThrow());
    }

    /**
     * 人工对账（流程 7.4）：INDETERMINATE 只能人工处置——
     * 确认生效→SUCCEEDED；确认未生效→FAILED。对账只追加审计，不改发布历史（R-REL-06）。
     */
    public ChangeRequest reconcile(String changeNo, boolean confirmedEffective,
                                   String operatorId, String traceId) {
        return tx.execute(s -> {
            ChangeRequest cr = changes.findById(changeNo)
                    .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
            if (!ChangeRequestState.INDETERMINATE.name().equals(cr.getStatus())) {
                throw new NcgException(NcgErrorCode.REL_409_001,
                        "only INDETERMINATE change can be reconciled, current: " + cr.getStatus());
            }
            cr.setStatus(confirmedEffective
                    ? ChangeRequestState.SUCCEEDED.name()
                    : ChangeRequestState.FAILED.name());
            cr.setUpdatedAt(Instant.now());
            ChangeRequest saved = changes.save(cr);
            auditWriter.append(new AuditWriter.Command(
                    ActorType.USER, operatorId, null, AuditAction.RELEASE_RECONCILE,
                    "CHANGE", changeNo, null, null, confirmedEffective, null, changeNo, traceId));
            return saved;
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 发布窗口（R-REL-01 预检六项之一、R-REL-05）：按环境配置，默认全天；
     * 窗口外阻断所有发布类写入（REL-422-001），P0 无紧急发布通道。
     */
    static void checkReleaseWindow(Env env) {
        java.time.LocalTime start = env.getReleaseWindowStart();
        java.time.LocalTime end = env.getReleaseWindowEnd();
        if (start == null && end == null) {
            return;
        }
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        boolean inside = start.isBefore(end)
                ? (!now.isBefore(start) && now.isBefore(end))
                : (!now.isBefore(start) || now.isBefore(end));
        if (!inside) {
            throw new NcgException(NcgErrorCode.REL_422_001,
                    "outside release window for env " + env.getEnvCode()
                            + " [" + start + "," + end + ")");
        }
    }

    /** R-REL-07：发布成功后比对订阅实例与提交时影响面快照，差异返回给审计（缺失实例告警日志）。 */
    private Jsonb detectSubscriberDiff(EnvId envId, ConfigRef ref, ChangeRequest cr) {
        try {
            SubscriberSnapshot after = port.subscribers(envId, ref);
            java.util.Set<String> current = after.subscribers().stream()
                    .map(Subscriber::address).collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> frozen = new java.util.TreeSet<>();
            String impactJson = cr.getImpactSnapshot() == null ? "" : cr.getImpactSnapshot().json();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"address\"\\s*:\\s*\"([^\"]+)\"").matcher(impactJson);
            while (m.find()) {
                frozen.add(m.group(1));
            }
            java.util.List<String> missing = frozen.stream().filter(a -> !current.contains(a)).sorted().toList();
            java.util.List<String> added = current.stream().filter(a -> !frozen.contains(a)).sorted().toList();
            if (missing.isEmpty() && added.isEmpty()) {
                return Jsonb.empty();
            }
            if (!missing.isEmpty()) {
                log.warn("R-REL-07 subscriber diff after release of {}: {} instance(s) in impact snapshot missing from subscribers: {}",
                        cr.getChangeNo(), missing.size(), missing);
            }
            return Jsonb.of("{\"missing\":" + strList(missing) + ",\"new\":" + strList(added) + "}");
        } catch (RuntimeException e) {
            log.warn("R-REL-07 subscriber diff skipped for {}: {}", cr.getChangeNo(), e.getMessage());
            return Jsonb.empty();
        }
    }

    private static String strList(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * 灰度阶段记录（R-REL-03：放量/转全量结果必须留痕）。
     * 发布记录终态不可改（R-REL-06），阶段结果只追加审计（RELEASE_RESULT）。
     */
    public void recordGrayPhase(String releaseId, String phase, String note,
                                String operatorId, String traceId) {
        ReleaseRecord rec = releases.findById(releaseId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "release not found: " + releaseId));
        String p = phase == null ? "" : phase.toUpperCase(java.util.Locale.ROOT);
        if (!p.equals("CANARY") && !p.equals("RAMPING") && !p.equals("FULL")) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "gray phase must be CANARY|RAMPING|FULL (放量=RAMPING, 转全量=FULL)");
        }
        String safeNote = note == null ? "" : note.replace("\\", "\\\\").replace("\"", "\\\"");
        Jsonb after = Jsonb.of("{\"gray_phase\":\"" + p + "\",\"note\":\"" + safeNote + "\"}");
        tx.executeWithoutResult(s -> auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.RELEASE_RESULT,
                "RELEASE", releaseId, null, after, true, null, rec.getChangeNo(), traceId)));
    }

    private String lastApprover(String changeNo) {
        return approvals.findByChangeNoOrderByCreatedAtAsc(changeNo).stream()
                .filter(a -> "APPROVE".equals(a.getAction()))
                .map(Approval::getActorId)
                .reduce((first, second) -> second)   // 最后一个 APPROVE
                .orElse(null);
    }

    private static NcgErrorCode rejectCode(RejectReason reason) {
        return switch (reason) {
            case BASELINE -> NcgErrorCode.REL_412_001;
            case AUTH -> NcgErrorCode.AUTH_401_002;
            case UNAVAILABLE -> NcgErrorCode.REL_503_001;
            case INVALID -> NcgErrorCode.REL_422_001;
        };
    }

    private static String receiptJson(Map<String, Object> receipt, GraySpec gray) {
        Map<String, Object> merged = new java.util.TreeMap<>(receipt == null ? Map.of() : receipt);
        if (gray != null) {
            merged.put("gray_method", gray.method());
            if (gray.condition() != null) {
                merged.put("gray_condition", gray.condition());
            }
            if (gray.observationWindowMinutes() != null) {
                merged.put("gray_observation_window_minutes", gray.observationWindowMinutes());
            }
        }
        return receiptJson(merged);
    }

    private static String receiptJson(Map<String, Object> receipt) {
        if (receipt == null || receipt.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(receipt).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "\\\"")).append("\":\"")
              .append(String.valueOf(e.getValue()).replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}

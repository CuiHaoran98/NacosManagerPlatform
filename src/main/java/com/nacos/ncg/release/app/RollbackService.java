package com.nacos.ncg.release.app;

import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.IdempotencyKey;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.PublishOutcome;
import com.nacos.ncg.adapter.nacos.port.RejectReason;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.catalog.domain.RecycleBinItem;
import com.nacos.ncg.catalog.repo.RecycleBinItemRepository;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.change.app.ChangeDraftService;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.release.domain.ReleaseRecord;
import com.nacos.ncg.release.repo.ReleaseRecordRepository;
import com.nacos.ncg.shared.ContentDigest;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.PermissionPolicy;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.crypto.CryptoService;
import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 回滚与恢复（FR-08 / 流程 7.6~7.7）：
 * 回滚目标 = 平台任意已 VERIFIED 版本；上一版本由原发布链直接执行（填原因），
 * 更早版本自动创建 ROLLBACK 变更单走审批；新 ReleaseRecord 只追加、不覆盖历史（R-REL-06）。
 * 恢复 = 回收站条目重写上次发布内容（Release 写+读回），同名冲突必须先处理（RB-409-001）。
 */
@Service
public class RollbackService {

    private final ChangeRequestRepository changes;
    private final ReleaseRecordRepository releases;
    private final com.nacos.ncg.approval.repo.ApprovalRepository approvals;
    private final ConfigMappingRepository mappings;
    private final RecycleBinItemRepository recycleBin;
    private final CatalogService catalog;
    private final ChangeDraftService drafts;
    private final NacosConfigPort port;
    private final CryptoService crypto;
    private final AuditWriter auditWriter;
    private final TransactionTemplate tx;
    private final PermissionPolicy policy = new PermissionPolicy();

    public RollbackService(ChangeRequestRepository changes, ReleaseRecordRepository releases,
                           com.nacos.ncg.approval.repo.ApprovalRepository approvals,
                           ConfigMappingRepository mappings, RecycleBinItemRepository recycleBin,
                           CatalogService catalog, ChangeDraftService drafts, NacosConfigPort port,
                           CryptoService crypto, AuditWriter auditWriter,
                           PlatformTransactionManager transactionManager) {
        this.changes = changes;
        this.releases = releases;
        this.approvals = approvals;
        this.mappings = mappings;
        this.recycleBin = recycleBin;
        this.catalog = catalog;
        this.drafts = drafts;
        this.port = port;
        this.crypto = crypto;
        this.auditWriter = auditWriter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 回滚结果：直接执行=既有变更单+新发布记录；更早版本=新建的 ROLLBACK 草稿单（status=DRAFT）。 */
    public record RollbackResult(String mode, ReleaseRecord record, ChangeRequest change) { }

    /**
     * 回滚到 targetReleaseId 对应版本（流程 7.6）。
     * 目标必须是同 mapping 的 VERIFIED 发布记录；
     * 目标=上一版本→原发布链直接执行（发布人≠审批人，填原因≥10 字）；
     * 更早版本→创建 changeType=ROLLBACK 草稿单，走提交→审批→发布（返回 DRAFT 单）。
     */
    public RollbackResult rollback(String changeNo, String targetReleaseId, String reason,
                                   String operatorId, String traceId) {
        if (reason == null || reason.trim().length() < 10) {
            throw new NcgException(NcgErrorCode.CR_400_001, "rollback reason must be at least 10 characters");
        }
        ChangeRequest cr = changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
        ReleaseRecord target = releases.findById(targetReleaseId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "release not found: " + targetReleaseId));
        if (!target.getMappingId().equals(cr.getMappingId())) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "target release belongs to a different mapping");
        }
        if (!"VERIFIED".equals(target.getOutcome())) {
            throw new NcgException(NcgErrorCode.REL_409_001,
                    "rollback target must be a VERIFIED release, got " + target.getOutcome());
        }

        // 目标版本内容（平台侧密文解密，不依赖 Nacos 历史）
        ChangeRequest targetChange = changes.findById(target.getChangeNo())
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001,
                        "target release change not found: " + target.getChangeNo()));
        String content = crypto.decrypt(targetChange.getContentCiphertext());

        boolean immediatePrevious = isImmediatePrevious(cr.getMappingId(), targetReleaseId);
        if (!immediatePrevious) {
            // 更早版本：建回滚单走审批（流程 7.6）
            ChangeRequest draft = drafts.open(cr.getAppId(), cr.getEnvId(), cr.getMappingId(),
                    "ROLLBACK", "Rollback to " + target.getTargetVersion(), reason,
                    content, operatorId, null, traceId);
            return new RollbackResult("DRAFT_REQUIRED", null, draft);
        }

        // 上一版本直接执行：发布人≠审批人 或 发布人=应用负责人（REL-403-001）
        App app = catalog.getApp(cr.getAppId());
        String approverId = lastApprover(changeNo);
        if (!policy.canPublish(operatorId, approverId, app.getOwnerId())) {
            throw new NcgException(NcgErrorCode.REL_403_001,
                    "publisher must differ from approver (or be app owner)");
        }
        ReleaseRecord record = publishVersion(cr, content, "ROLLBACK",
                "rollback-to-" + target.getTargetVersion(),
                changeNo + ":rollback:" + targetReleaseId,
                operatorId, traceId);
        return new RollbackResult("EXECUTED", record, cr);
    }

    /**
     * 恢复回收站条目（流程 7.7）：重写上次发布内容并读回；
     * 同名活跃映射存在→RB_409_001（必须覆盖并重新审批或放弃）；超过 purge_at→RB_410_001 不可恢复。
     */
    public ReleaseRecord restore(String itemId, String operatorId, String traceId) {
        RecycleBinItem item = recycleBin.findById(itemId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "recycle item not found: " + itemId));
        if (!"PENDING".equals(item.getRestoreStatus())) {
            throw new NcgException(NcgErrorCode.RB_409_001,
                    "recycle item already " + item.getRestoreStatus());
        }
        Instant now = Instant.now();
        if (now.isAfter(item.getPurgeAt())) {
            throw new NcgException(NcgErrorCode.RB_410_001,
                    "recycle item expired at " + item.getPurgeAt() + ", restore not allowed (R-SYS-06)");
        }
        ConfigMapping mapping = mappings.findById(item.getMappingId())
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001,
                        "mapping not found: " + item.getMappingId()));
        if (!"DELETED".equals(mapping.getStatus())) {
            throw new NcgException(NcgErrorCode.RB_409_001, "mapping is not deleted");
        }
        // 同名冲突：另一条活跃映射占用同一 namespace/group/dataId
        boolean clash = mappings.existsByNamespaceAndGroupNameAndDataIdAndStatusNot(
                mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId(), "DELETED");
        if (clash) {
            throw new NcgException(NcgErrorCode.RB_409_001,
                    "a managed mapping with the same namespace/group/dataId exists; "
                            + "overwrite via a new change request or abandon the restore");
        }

        // 恢复目录记录
        mapping.setStatus("MANAGED");
        mapping.setUpdatedAt(now);
        mappings.save(mapping);
        item.setRestoreStatus("RESTORED");
        item.setUpdatedAt(now);
        recycleBin.save(item);

        // 内容重写（有历史内容时走 Release 写+读回；无内容仅恢复目录）
        Optional<ChangeRequest> lastVersion = lastVerifiedChange(mapping.getMappingId());
        ReleaseRecord record = null;
        if (lastVersion.isPresent()) {
            String content = crypto.decrypt(lastVersion.get().getContentCiphertext());
            record = publishVersion(lastVersion.get(), content, "RESTORE",
                    "restore-" + item.getItemId(), mapping.getMappingId() + ":restore:" + item.getItemId(),
                    operatorId, traceId);
        } else {
            tx.executeWithoutResult(s -> auditWriter.append(AuditWriter.Command.user(operatorId, null,
                    AuditAction.RESTORE, "mapping", mapping.getMappingId(), traceId)));
        }
        return record;
    }

    /**
     * 契约 #46 onConflict=OVERRIDE_AND_REAPPROVE：同名冲突时不再直接恢复，
     * 改为对占位的同名活跃映射创建 changeType=RESTORE 变更单（覆盖并重新审批），
     * 回收条目视为已被该变更单接管（RESTORED）。
     */
    public ChangeRequest restoreAsChange(String itemId, String operatorId, String traceId) {
        RecycleBinItem item = recycleBin.findById(itemId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "recycle item not found: " + itemId));
        if (!"PENDING".equals(item.getRestoreStatus())) {
            throw new NcgException(NcgErrorCode.RB_409_001,
                    "recycle item already " + item.getRestoreStatus());
        }
        Instant now = Instant.now();
        if (now.isAfter(item.getPurgeAt())) {
            throw new NcgException(NcgErrorCode.RB_410_001,
                    "recycle item expired at " + item.getPurgeAt() + ", restore not allowed (R-SYS-06)");
        }
        ConfigMapping mapping = mappings.findById(item.getMappingId())
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001,
                        "mapping not found: " + item.getMappingId()));
        ConfigMapping clash = mappings.findFirstByNamespaceAndGroupNameAndDataIdAndStatusNot(
                        mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId(), "DELETED")
                .orElseThrow(() -> new NcgException(NcgErrorCode.RB_409_001,
                        "no clashing mapping; use plain restore"));
        String content = lastVerifiedChange(mapping.getMappingId())
                .map(v -> crypto.decrypt(v.getContentCiphertext()))
                .orElse("");
        ChangeRequest cr = drafts.open(clash.getAppId(), clash.getEnvId(), clash.getMappingId(),
                "RESTORE",
                "Restore " + mapping.getDataId() + " over existing mapping",
                "Recycle restore with overwrite and re-approval (item " + itemId + ")",
                content, operatorId, null, traceId);
        item.setRestoreStatus("RESTORED");
        item.setUpdatedAt(now);
        recycleBin.save(item);
        tx.executeWithoutResult(s -> auditWriter.append(AuditWriter.Command.user(operatorId, null,
                AuditAction.RESTORE, "recycle_item", itemId, "changeNo=" + cr.getChangeNo())));
        return cr;
    }

    /** 软删映射（FR-08）：进回收站 purge_at=+30 天，MAPPING_DELETE 审计；Nacos 侧清空走 DELETE 变更单。 */
    public RecycleBinItem softDelete(String mappingId, String operatorId, String traceId) {
        ConfigMapping mapping = mappings.findById(mappingId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "mapping not found: " + mappingId));
        if ("DELETED".equals(mapping.getStatus())) {
            throw new NcgException(NcgErrorCode.RB_409_001, "mapping already deleted");
        }
        Instant now = Instant.now();
        // 复用同一 mapping 的回收条目（uk_recycle_mapping 一映射一条）：删除→恢复→再删除时重置回 PENDING，
        // 而非插入新行撞唯一键（联调 D8：二次软删 DuplicateKeyException → SYS-500-001）。
        RecycleBinItem item = recycleBin.findByMappingId(mappingId).orElseGet(RecycleBinItem::new);
        if (item.getItemId() == null || item.getItemId().isBlank()) {
            item.setItemId(Ulid.generate());
        }
        item.setMappingId(mappingId);
        item.setDeletedAt(now);
        item.setDeletedBy(operatorId);
        item.setPurgeAt(now.plus(30, ChronoUnit.DAYS));
        item.setRestoreStatus("PENDING");
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        RecycleBinItem saved = tx.execute(s -> {
            mapping.setStatus("DELETED");
            mapping.setUpdatedAt(now);
            mappings.save(mapping);
            RecycleBinItem savedItem = recycleBin.save(item);
            auditWriter.append(AuditWriter.Command.user(operatorId, null,
                    AuditAction.MAPPING_DELETE, "mapping", mappingId, traceId));
            return savedItem;
        });
        return saved;
    }

    // ---- 内部：三事务发布（唯一写入口复用同一模式，C-ARC-03）----

    private ReleaseRecord publishVersion(ChangeRequest cr, String content, String sourceType,
                                         String targetVersion, String releaseKey,
                                         String operatorId, String traceId) {
        ConfigMapping mapping = mappings.findById(cr.getMappingId()).orElseThrow();
        Env env = catalog.getEnv(cr.getEnvId());
        ConfigRef ref = new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId());
        EnvId envId = new EnvId(env.getEnvCode());
        ReleaseService.checkReleaseWindow(env); // R-REL-05：回滚/恢复同为发布类写入

        // 预检（事务外读，只读可重试 1 次）
        String currentMd5;
        try {
            currentMd5 = port.read(envId, ref).md5();
        } catch (NcgException e) {
            if (e.errorCode() == NcgErrorCode.SYS_404_001) {
                // 配置不存在=RESTORE 重建场景的正常起点：CAS 创建（expected=null）
                currentMd5 = null;
            } else {
                throw new NcgException(NcgErrorCode.REL_503_001,
                        "nacos unreachable for precheck: " + e.getMessage());
            }
        }

        String expectedMd5 = ContentDigest.md5Hex(content);
        Instant startedAt = Instant.now();

        // 事务 A：发布意图 + ReleaseRecord(WRITING)
        tx.executeWithoutResult(s -> {
            ReleaseRecord rec = new ReleaseRecord();
            rec.setReleaseId(Ulid.generate());
            rec.setReleaseKey(releaseKey);
            rec.setChangeNo(cr.getChangeNo());
            rec.setMappingId(cr.getMappingId());
            rec.setEnvId(cr.getEnvId());
            rec.setSourceType(sourceType);
            rec.setTargetVersion(targetVersion);
            rec.setOutcome("WRITING");
            rec.setExpectedMd5(expectedMd5);
            rec.setOperatorId(operatorId);
            rec.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
            rec.setStartedAt(startedAt);
            releases.save(rec);
            auditWriter.append(AuditWriter.Command.user(operatorId, null,
                    AuditAction.RELEASE_INTENT, "release", rec.getReleaseId(), traceId));
        });

        // 事务外：唯一写入口（不自动重试，C-ARC-05）
        PublishOutcome outcome;
        try {
            outcome = port.publish(envId, ref, content, currentMd5,
                    new IdempotencyKey(cr.getChangeNo(), targetVersion));
        } catch (RuntimeException e) {
            outcome = new PublishOutcome.Indeterminate("adapter error: " + e.getMessage(), Map.of());
        }

        // 事务 B：结果 + 审计
        PublishOutcome finalOutcome = outcome;
        ReleaseRecord finished = tx.execute(s -> {
            ReleaseRecord rec = releases.findByReleaseKey(releaseKey).orElseThrow();
            rec.setFinishedAt(Instant.now());
            rec.setNacosReceipt(Jsonb.of(receiptJson(finalOutcome)));
            if (finalOutcome instanceof PublishOutcome.Verified v) {
                rec.setOutcome("VERIFIED");
                rec.setReadbackMd5(v.readbackMd5());
                catalog.updateSnapshot(cr.getMappingId(), v.readbackMd5(), Instant.now());
            } else if (finalOutcome instanceof PublishOutcome.Rejected r) {
                rec.setOutcome("REJECTED");
                rec.setRejectReason(r.reason().name());
                rec.setErrorCode(rejectCode(r.reason()).code());
            } else {
                rec.setOutcome("INDETERMINATE");
                rec.setErrorCode(NcgErrorCode.REL_502_001.code());
            }
            ReleaseRecord saved = releases.save(rec);
            boolean verified = "VERIFIED".equals(saved.getOutcome());
            auditWriter.append(AuditWriter.Command.user(operatorId, null,
                    verified ? AuditAction.RELEASE_RESULT : AuditAction.RELEASE_RESULT,
                    "release", saved.getReleaseId(), traceId));
            return saved;
        });
        return finished;
    }

    private boolean isImmediatePrevious(String mappingId, String targetReleaseId) {
        List<ReleaseRecord> verified = releases.findByMappingIdOrderByStartedAtDesc(mappingId).stream()
                .filter(r -> !"REJECTED".equals(r.getOutcome()))  // 含 INDETERMINATE：对账确认生效后仍是版本序列的一环
                .toList();
        return verified.size() >= 2 && verified.get(1).getReleaseId().equals(targetReleaseId);
    }

    private Optional<ChangeRequest> lastVerifiedChange(String mappingId) {
        return releases.findByMappingIdOrderByStartedAtDesc(mappingId).stream()
                .filter(r -> !"REJECTED".equals(r.getOutcome()))  // 含 INDETERMINATE：对账确认生效后仍是版本序列的一环
                .findFirst()
                .flatMap(r -> changes.findById(r.getChangeNo()));
    }

    private String lastApprover(String changeNo) {
        return approvals.findByChangeNoOrderByCreatedAtAsc(changeNo).stream()
                .filter(a -> "APPROVE".equals(a.getAction()))
                .map(com.nacos.ncg.approval.domain.Approval::getActorId)
                .reduce((first, second) -> second)
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

    private static String receiptJson(PublishOutcome outcome) {
        Map<String, Object> receipt = switch (outcome) {
            case PublishOutcome.Verified v -> v.receipt();
            case PublishOutcome.Rejected r -> r.receipt();
            case PublishOutcome.Indeterminate i -> i.receipt();
        };
        StringBuilder sb = new StringBuilder("{");
        receipt.forEach((k, v) -> sb.append('"').append(k.replace("\"", "\\\"")).append("\":\"")
                .append(String.valueOf(v).replace("\"", "\\\"")).append("\","));
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        return sb.append('}').toString();
    }
}

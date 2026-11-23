package com.nacos.ncg.change.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.domain.ChangeRequestState;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.Snapshot;
import com.nacos.ncg.adapter.nacos.port.SubscriberSnapshot;
import com.nacos.ncg.shared.BaselineGuard;
import com.nacos.ncg.shared.ContentDigest;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.RiskAssessment;
import com.nacos.ncg.shared.RiskCalculator;
import com.nacos.ncg.shared.RiskLevel;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.crypto.CryptoService;
import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变更单草稿服务（FR-02 / 流程 7.1：建单-编辑-提交-取消）。
 * 提交按 7.1 三步走：事务外读取 Nacos 当前值与订阅者 → 事务内比对冻结
 * baseline_md5 / 影响面快照 / 风险 → 状态转 PENDING_APPROVAL；审计与状态同事务（C-ARC-06）。
 * 提交后 baseline 不回写（R-CR-04），即使 Nacos 侧继续变化（那属于基线漂移，发布前检出）。
 */
@Service
public class ChangeDraftService {

    public static final Set<String> ACTIVE_STATES = Set.of(
            "PENDING_APPROVAL", "APPROVED", "SCHEDULED", "RELEASING", "INDETERMINATE");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired private com.nacos.ncg.catalog.app.InstanceService instanceService;

    private final ChangeRequestRepository changes;
    private final CatalogService catalog;
    private final NacosConfigPort port;
    private final CryptoService crypto;
    private final AuditWriter auditWriter;
    private final RiskCalculator riskCalculator = new RiskCalculator();
    private final BaselineGuard baselineGuard = new BaselineGuard();
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final int contentMaxBytes;

    public ChangeDraftService(
            ChangeRequestRepository changes,
            CatalogService catalog,
            NacosConfigPort port,
            CryptoService crypto,
            AuditWriter auditWriter,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc,
            @Value("${ncg.content-max-bytes:1048576}") int contentMaxBytes) {
        this.changes = changes;
        this.catalog = catalog;
        this.port = port;
        this.crypto = crypto;
        this.auditWriter = auditWriter;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.contentMaxBytes = contentMaxBytes;
    }

    /** 建单（草稿）。content 对 CREATE/UPDATE 必填且加密存储；DRAFT 不写 Nacos。 */
    @org.springframework.transaction.annotation.Transactional
    public ChangeRequest open(
            String appId, String envId, String mappingId, String changeType,
            String title, String reason, String content,
            String submitterId, RiskLevel userRiskLevel, String traceId) {
        instanceService.requireWritable(envId);
        requireReason(reason);
        ConfigMapping mapping = catalog.getMapping(mappingId);
        Env env = catalog.getEnv(envId);
        boolean needsContent = "CREATE".equals(changeType) || "UPDATE".equals(changeType);
        if (needsContent && (content == null || content.isBlank())) {
            throw new NcgException(NcgErrorCode.CR_400_001, "content is required for " + changeType);
        }
        if (needsContent && content.getBytes(StandardCharsets.UTF_8).length > contentMaxBytes) {
            throw new NcgException(NcgErrorCode.CR_413_001,
                    "content exceeds limit " + contentMaxBytes + " bytes");
        }
        if (title == null || title.isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "title is required");
        }

        Instant now = Instant.now();
        ChangeRequest cr = new ChangeRequest();
        cr.setChangeNo(nextChangeNo(now));
        cr.setAppId(appId);
        cr.setEnvId(envId);
        cr.setMappingId(mappingId);
        cr.setChangeType(changeType);
        cr.setTitle(title);
        cr.setReason(reason);
        String effectiveContent = content == null ? "" : content;
        // schema 要求 content_sha256 NOT NULL：无内容草稿（如无历史的 RESTORE）按空串落密文与摘要
        cr.setContentCiphertext(crypto.encrypt(effectiveContent));
        cr.setContentSha256(ContentDigest.sha256Hex(effectiveContent));
        cr.setRiskLevel(userRiskLevel == null ? RiskLevel.LOW.name() : userRiskLevel.name());
        cr.setRiskFactors(Jsonb.empty());
        cr.setStatus(ChangeRequestState.DRAFT.name());
        cr.setSubmitterId(submitterId);
        cr.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
        cr.setCreatedAt(now);
        cr.setUpdatedAt(now);
        // 基线创建时记录（契约 §3.4 / TC-19）：非 CREATE 建单即快照当前 md5，提交时复核漂移
        if (!"CREATE".equals(changeType)) {
            try {
                Snapshot live = port.read(new EnvId(env.getEnvCode()),
                        new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId()));
                cr.setBaselineMd5(live.md5());
            } catch (RuntimeException e) {
                // 当前值不可得 → 基线留空，提交时冻结（旧行为）
            }
        }
        // 契约 §3.4 / TC-12：同一 DataId 已有进行中变更单时建单即拒绝（details 返已有 changeNo）
        var active = changes.findFirstByMappingIdAndStatusIn(cr.getMappingId(), ACTIVE_STATES);
        if (active.isPresent()) {
            throw new NcgException(NcgErrorCode.CR_409_001,
                    "该配置已有进行中变更单",
                    java.util.Map.of("changeNo", active.get().getChangeNo()));
        }
        ChangeRequest saved = saveOrConflict(cr);

        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, submitterId, null, AuditAction.CHANGE_CREATE,
                "CHANGE", saved.getChangeNo(), null, null, true, null, saved.getChangeNo(), traceId));
        return saved;
    }

    /** 编辑草稿（仅 DRAFT）：标题/原因/内容可改；内容变化重算 sha256。 */
    @org.springframework.transaction.annotation.Transactional
    public ChangeRequest edit(String changeNo, String title, String reason, String content,
                              String actorId, String traceId) {
        ChangeRequest cr = requireState(changeNo, Set.of(ChangeRequestState.DRAFT.name()));
        if (reason != null) {
            requireReason(reason);
            cr.setReason(reason);
        }
        if (title != null && !title.isBlank()) {
            cr.setTitle(title);
        }
        if (content != null) {
            if (content.getBytes(StandardCharsets.UTF_8).length > contentMaxBytes) {
                throw new NcgException(NcgErrorCode.CR_413_001, "content exceeds limit " + contentMaxBytes);
            }
            cr.setContentCiphertext(crypto.encrypt(content));
            cr.setContentSha256(ContentDigest.sha256Hex(content));
        }
        cr.setUpdatedAt(Instant.now());
        ChangeRequest saved = saveOrConflict(cr);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, actorId, null, AuditAction.CHANGE_UPDATE,
                "CHANGE", changeNo, null, null, true, null, changeNo, traceId));
        return saved;
    }

    /** 取消（DRAFT/PENDING_APPROVAL/APPROVED/SCHEDULED → CANCELLED）。 */
    @org.springframework.transaction.annotation.Transactional
    public ChangeRequest cancel(String changeNo, String reason, String actorId, String traceId) {
        ChangeRequest cr = requireState(changeNo, Set.of(
                ChangeRequestState.DRAFT.name(), ChangeRequestState.PENDING_APPROVAL.name(),
                ChangeRequestState.APPROVED.name(), ChangeRequestState.SCHEDULED.name()));
        cr.setStatus(ChangeRequestState.CANCELLED.name());
        cr.setClosedAt(Instant.now());
        cr.setUpdatedAt(Instant.now());
        ChangeRequest saved = saveOrConflict(cr);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, actorId, null, AuditAction.CHANGE_CANCEL,
                "CHANGE", changeNo, null, null, true, null, changeNo, traceId));
        return saved;
    }

    /**
     * 提交审批（流程 7.1）：事务外读 Nacos 当前值与订阅者 → 事务内冻结基线/影响面/风险 → PENDING_APPROVAL。
     * 同映射已有活动单 → CR-409-001（uk_active_change 兜底并发）。
     */
    public ChangeRequest submit(String changeNo, String actorId, RiskLevel userRiskLevel, String traceId) {

        instanceService.requireWritable(changes.findById(changeNo).orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo)).getEnvId());
        ChangeRequest snapshot = changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
        if (!ChangeRequestState.DRAFT.name().equals(snapshot.getStatus())) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "only DRAFT can be submitted, current=" + snapshot.getStatus());
        }
        ConfigMapping mapping = catalog.getMapping(snapshot.getMappingId());
        Env env = catalog.getEnv(snapshot.getEnvId());
        ConfigRef ref = new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId());
        EnvId envId = new EnvId(env.getEnvCode());

        // --- 事务外读取（外部调用不进事务，C-ARC-09） ---
        String currentMd5 = null;
        String currentContent = null;
        try {
            Snapshot live = port.read(envId, ref);
            currentMd5 = live.md5();
            currentContent = live.content();
        } catch (NcgException e) {
            if (!"CREATE".equals(snapshot.getChangeType())) {
                throw e; // UPDATE/DELETE/ROLLBACK 必须有现存配置
            }
            // CREATE：配置尚不存在，基线为“不存在”
        }
        List<Map<String, Object>> subscribers = List.of();
        Integer subscriberCount = null;
        try {
            SubscriberSnapshot snap = port.subscribers(envId, ref);
            subscribers = snap.subscribers().stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("address", s.address());
                        m.put("app", s.appName());
                        return m;
                    })
                    .toList();
            subscriberCount = (int) snap.subscribers().stream().filter(s -> s.knownApp()).count();
        } catch (RuntimeException e) {
            // 订阅者不可得 → 影响面未知（R-IMP-02），风险上调一级
            subscriberCount = null;
        }

        int diffLines = countChangedLines(currentContent, decryptTarget(snapshot));
        boolean recentFailure = hasRecentFailure(snapshot.getMappingId());

        RiskAssessment risk = riskCalculator.assess(
                "prod".equalsIgnoreCase(env.getEnvCode()),
                mapping.isSensitive(),
                "DELETE".equals(snapshot.getChangeType()),
                diffLines,
                subscriberCount,
                recentFailure,
                isOffHours(Instant.now()),
                userRiskLevel);

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("subscriberCount", subscriberCount);
        impact.put("subscribers", subscribers);
        impact.put("diffLineCount", diffLines);
        impact.put("collectedAt", Instant.now().toString());
        impact.put("impactUnknown", subscriberCount == null);

        final String frozenBaselineMd5 = currentMd5; // 提交时冻结基线（R-CR-04）

        // 基线漂移复核（TC-19 / 契约 §3.4）：创建时已记 baseline_md5，提交前比对；
        // 不一致 → CR-412-001，状态不前进（草稿保留）。
        if (snapshot.getBaselineMd5() != null && currentMd5 != null
                && !snapshot.getBaselineMd5().equals(currentMd5)) {
            throw new NcgException(NcgErrorCode.CR_412_001,
                    "基线漂移：配置当前值与创建时记录不一致",
                    Map.of("baselineMd5", snapshot.getBaselineMd5(), "currentMd5", currentMd5));
        }

        // --- 事务内冻结 + 状态迁移 + 审计（同事务） ---
        return tx.execute(status -> {
            ChangeRequest cr = changes.findById(changeNo)
                    .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found"));
            if (!ChangeRequestState.DRAFT.name().equals(cr.getStatus())) {
                throw new NcgException(NcgErrorCode.CR_400_001, "state changed concurrently: " + cr.getStatus());
            }
            var conflict = changes.findFirstByMappingIdAndStatusIn(cr.getMappingId(), ACTIVE_STATES);
            if (conflict.isPresent()) {
                throw new NcgException(NcgErrorCode.CR_409_001,
                        "active change exists: " + conflict.get().getChangeNo(),
                        java.util.Map.of("changeNo", conflict.get().getChangeNo()));
            }
            cr.setBaselineMd5(frozenBaselineMd5);    // 冻结；提交后永不回写（R-CR-04）
            cr.setImpactSnapshot(toJsonb(impact));   // 冻结影响面（R-IMP-01）
            cr.setRiskLevel(risk.level().name());
            cr.setRiskFactors(toJsonb(Map.of("factors", risk.factors(), "unknowns", risk.unknowns())));
            cr.setStatus(ChangeRequestState.PENDING_APPROVAL.name());
            cr.setUpdatedAt(Instant.now());
            ChangeRequest saved = saveOrConflict(cr);
            auditWriter.append(new AuditWriter.Command(
                    ActorType.USER, actorId, null, AuditAction.CHANGE_SUBMIT,
                    "CHANGE", changeNo, null, null, true, null, changeNo, traceId));
            return saved;
        });
    }

    // ---------- 内部 ----------

    /** 草稿目标内容明文（契约 GET /changes/{changeNo} 详情展示；仅 web 层调用）。 */
    public String contentOf(ChangeRequest cr) {
        return decryptTarget(cr);
    }

    private ChangeRequest requireState(String changeNo, Set<String> allowed) {
        ChangeRequest cr = changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
        if (!allowed.contains(cr.getStatus())) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "state " + cr.getStatus() + " does not allow this action; allowed=" + allowed);
        }
        return cr;
    }

    private void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 10) {
            throw new NcgException(NcgErrorCode.CR_400_001, "reason must be at least 10 characters (R-CR-01)");
        }
    }

    private ChangeRequest saveOrConflict(ChangeRequest cr) {
        try {
            return changes.save(cr);
        } catch (DataIntegrityViolationException e) {
            throw new NcgException(NcgErrorCode.CR_409_001,
                    "active change conflict on mapping " + cr.getMappingId(),
                    java.util.Collections.emptyMap(), e);
        }
    }

    /** CR-YYYYMMDD-NNNN；同日 seq 分配用 advisory lock 串行化。 */
    private String nextChangeNo(Instant now) {
        String day = DAY.format(now.atZone(ZoneId.of("UTC")));
        String prefix = "CR-" + day + "-";
        jdbc.execute("SELECT pg_advisory_xact_lock((" + Math.abs(prefix.hashCode()) + ")::bigint)");
        int next = changes.findLatestChangeNo(prefix + "%")
                .map(no -> Integer.parseInt(no.substring(prefix.length())) + 1)
                .orElse(1);
        return prefix + String.format("%04d", next);
    }

    private String decryptTarget(ChangeRequest cr) {
        return cr.getContentCiphertext() == null ? null : crypto.decrypt(cr.getContentCiphertext());
    }

    /** 粗粒度 Diff 规模：去掉公共首尾行后剩余行数（精确 Diff 在 UI 层计算）。 */
    static int countChangedLines(String before, String after) {
        if (before == null && after == null) {
            return 0;
        }
        List<String> a = before == null ? List.of() : before.lines().toList();
        List<String> b = after == null ? List.of() : after.lines().toList();
        int head = 0;
        while (head < a.size() && head < b.size() && a.get(head).equals(b.get(head))) {
            head++;
        }
        int tail = 0;
        while (tail < a.size() - head && tail < b.size() - head
                && a.get(a.size() - 1 - tail).equals(b.get(b.size() - 1 - tail))) {
            tail++;
        }
        return (a.size() - head - tail) + (b.size() - head - tail);
    }

    private boolean hasRecentFailure(String mappingId) {
        Instant since = Instant.now().minusSeconds(7 * 24 * 3600L);
        return changes.findByMappingIdOrderByCreatedAtDesc(mappingId).stream()
                .anyMatch(c -> c.getCreatedAt().isAfter(since)
                        && ("FAILED".equals(c.getStatus()) || "INDETERMINATE".equals(c.getStatus())));
    }

    /** 非工作时间：周末或 09:00-18:00（Asia/Shanghai）之外 [TBD，A-01 闭环后固化]。 */
    static boolean isOffHours(Instant instant) {
        ZonedDateTime zdt = instant.atZone(BIZ_ZONE);
        if (zdt.getDayOfWeek() == DayOfWeek.SATURDAY || zdt.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }
        LocalTime t = zdt.toLocalTime();
        return t.isBefore(LocalTime.of(9, 0)) || !t.isBefore(LocalTime.of(18, 0));
    }

    private Jsonb toJsonb(Map<String, ?> map) {
        try {
            return Jsonb.of(json.writeValueAsString(map));
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }
}

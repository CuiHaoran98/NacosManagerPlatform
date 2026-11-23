package com.nacos.ncg.integration;

import com.nacos.ncg.NcgWebApplication;
import com.nacos.ncg.adapter.nacos.fake.FakeNacosConfigAdapter;
import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.approval.app.ApprovalService;
import com.nacos.ncg.audit.app.DirectScanService;
import com.nacos.ncg.audit.domain.DirectChangeEvent;
import com.nacos.ncg.audit.domain.ScanCursor;
import com.nacos.ncg.audit.repo.DirectChangeEventRepository;
import com.nacos.ncg.audit.repo.ScanCursorRepository;
import com.nacos.ncg.auth.app.AccountService;
import com.nacos.ncg.batch.app.BatchService;
import com.nacos.ncg.batch.domain.Batch;
import com.nacos.ncg.batch.domain.BatchItem;
import com.nacos.ncg.batch.repo.BatchItemRepository;
import com.nacos.ncg.batch.repo.BatchRepository;
import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.catalog.repo.AppRepository;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.catalog.repo.EnvRepository;
import com.nacos.ncg.change.app.ChangeDraftService;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.notification.domain.Notification;
import com.nacos.ncg.notification.repo.NotificationRepository;
import com.nacos.ncg.release.app.ReleaseService;
import com.nacos.ncg.release.repo.ReleaseRecordRepository;
import com.nacos.ncg.scheduler.app.ScheduleService;
import com.nacos.ncg.scheduler.domain.ScheduleJob;
import com.nacos.ncg.scheduler.repo.ScheduleJobRepository;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 失败路径与直连检测集成测试（AC-13/14/21/22 + R-AUD-05，Testcontainers PostgreSQL 16）。
 * 覆盖运行时冒烟无法经 HTTP 触发的场景（fake 进程内注入）：
 * 直连修改报警 / 已发布变更匹配不误报 / 检测盲区 / 批量部分成功只重试失败项 / 定时复核失败转回待发布。
 * 无 Docker 环境自动跳过（disabledWithoutDocker）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = NcgWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "ncg.crypto.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "ncg.crypto.key-id=test-key",
                "ncg.nacos.mode=fake"
        })
@ActiveProfiles("local")
class DirectChangeAndFailurePathIntegrationTest {

    

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired com.nacos.ncg.catalog.app.InstanceService instanceService;
    @Autowired com.nacos.ncg.catalog.repo.InstanceRepository instances;
    @Autowired com.nacos.ncg.auth.app.GrantService grants;
    @Autowired com.nacos.ncg.web.MappingAdminController mappingAdminController;
    @Autowired AccountService accountService;
    @Autowired ChangeDraftService drafts;
    @Autowired ChangeRequestRepository changeRequests;
    @Autowired ApprovalService approvals;
    @Autowired ReleaseService releases;
    @Autowired ReleaseRecordRepository releaseRecords;
    @Autowired BatchService batchService;
    @Autowired BatchRepository batches;
    @Autowired BatchItemRepository batchItems;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleJobRepository scheduleJobs;
    @Autowired DirectScanService directScanService;
    @Autowired DirectChangeEventRepository directChangeEvents;
    @Autowired ScanCursorRepository scanCursors;
    @Autowired NotificationRepository notifications;
    @Autowired AppRepository apps;
    @Autowired EnvRepository envs;
    @Autowired ConfigMappingRepository mappings;
    @Autowired NacosConfigPort port;
    @Autowired com.nacos.ncg.notification.app.NotificationOutbox outbox;
    @Autowired com.nacos.ncg.notification.app.NotificationDispatcher dispatcher;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.nacos.ncg.envsync.app.EnvSyncService envSyncService;
    @Autowired com.nacos.ncg.audit.repo.AuditEventRepository auditEvents;
    @Autowired com.nacos.ncg.release.app.RollbackService rollbackService;
    @Autowired com.nacos.ncg.catalog.repo.RecycleBinItemRepository recycleBin;

    // ---- 装配 ----

    private record Fixture(App app, Env env, ConfigMapping mapping, ConfigRef ref, EnvId envId) {}

    private Fixture fixture(String dataId) {
        App app = new App();
        app.setAppId(Ulid.generate());
        app.setAppCode("a" + Ulid.generate().substring(0, 10)); // uk_app_code 唯一，避免重跑冲突
        app.setName("App " + dataId);
        app.setOwnerId("owner-1");
        app.setStatus("ACTIVE");
        app.setCreatedAt(Instant.now());
        app.setUpdatedAt(Instant.now());
        apps.save(app);

        Env env = new Env();
        env.setEnvId(Ulid.generate());
        env.setEnvCode("e" + Ulid.generate().substring(0, 10)); // env_code varchar(16)
        env.setDisplayName("dev " + dataId);
        env.setSortOrder(1);
        env.setStatus("ACTIVE");
        env.setCreatedAt(Instant.now());
        env.setUpdatedAt(Instant.now());
        envs.save(env);

        ConfigMapping mapping = new ConfigMapping();
        mapping.setMappingId(Ulid.generate());
        mapping.setAppId(app.getAppId());
        mapping.setEnvId(env.getEnvId());
        mapping.setNamespace("public");
        mapping.setGroupName("DEFAULT_GROUP");
        mapping.setDataId(dataId);
        mapping.setSensitive(false);
        mapping.setOwnerId("owner-1");
        mapping.setStatus("MANAGED");
        mapping.setCreatedAt(Instant.now());
        mapping.setUpdatedAt(Instant.now());
        mappings.save(mapping);

        ConfigRef ref = new ConfigRef("public", "DEFAULT_GROUP", dataId);
        return new Fixture(app, env, mapping, ref, new EnvId(env.getEnvCode()));
    }

    private String approvedChange(Fixture f, String content) {
        ChangeRequest cr = drafts.open(f.app().getAppId(), f.env().getEnvId(), f.mapping().getMappingId(),
                "CREATE", "t " + f.mapping().getDataId(), "integration change for " + f.mapping().getDataId(),
                content, "alice", RiskLevel.LOW, null);
        drafts.submit(cr.getChangeNo(), "alice", RiskLevel.LOW, null);
        approvals.decide(cr.getChangeNo(), "APPROVE", null, null, "bob", null);
        return cr.getChangeNo();
    }

    // ---- AC-13：直连修改报警 ----

    @Test
    void directChangeUnauthorizedAlarm() {
        Fixture f = fixture("dc-unauth.yaml");
        // 外部直接写入 Nacos（无任何平台发布记录）
        ((FakeNacosConfigAdapter) port).seedExternalChange(f.envId(), f.ref(), "hacked: true", "external-op");

        directScanService.scan(f.env().getEnvId(), f.env().getEnvCode());

        List<DirectChangeEvent> events = directChangeEvents.findByVerdict("UNAUTHORIZED");
        assertFalse(events.isEmpty(), "直连修改必须产生 UNAUTHORIZED 事件");
        DirectChangeEvent e = events.get(0);
        assertEquals("dc-unauth.yaml", e.getDataId());
        // 通知入队 + 审计（DIRECT_CHANGE_DETECTED）由 scan 内完成
        boolean notified = notifications.findByStatusInOrderByCreatedAtAsc(List.of("QUEUED", "SENDING", "SENT", "RETRYING"))
                .stream().anyMatch(n -> "DIRECT_CHANGE_UNAUTHORIZED".equals(n.getEventType()));
        assertTrue(notified, "直连修改必须告警（站内/企微）");
    }

    // ---- R-AUD-05：平台已发布变更匹配，不误报 ----

    @Test
    void directChangeMatchedDoesNotAlarm() {
        Fixture f = fixture("dc-match.yaml");
        String changeNo = approvedChange(f, "key: match");
        releases.release(changeNo, "v1", "carol", null);
        // 与发布记录 readback 相同的“外部变更”（例如平台账号直连）→ 应 MATCHED
        ((FakeNacosConfigAdapter) port).seedExternalChange(f.envId(), f.ref(), "key: match", "platform-account");

        directScanService.scan(f.env().getEnvId(), f.env().getEnvCode());

        List<DirectChangeEvent> matched = directChangeEvents.findByVerdict("MATCHED");
        assertFalse(matched.isEmpty(), "已发布内容的变更应判 MATCHED");
        boolean unauthForThis = directChangeEvents.findByVerdict("UNAUTHORIZED").stream()
                .anyMatch(e -> "dc-match.yaml".equals(e.getDataId()));
        assertFalse(unauthForThis, "MATCHED 不得产生 UNAUTHORIZED 报警");
    }

    // ---- AC-14：历史缺口标检测盲区，不推断无变更 ----

    @Test
    void detectionBlindSpotMarked() {
        Fixture f = fixture("dc-blind.yaml");
        ((FakeNacosConfigAdapter) port).setEnvDown(f.envId(), true);

        directScanService.scan(f.env().getEnvId(), f.env().getEnvCode());
        directScanService.scan(f.env().getEnvId(), f.env().getEnvCode());
        directScanService.scan(f.env().getEnvId(), f.env().getEnvCode());

        ScanCursor cursor = scanCursors.findById(f.env().getEnvId()).orElseThrow();
        assertTrue(cursor.getConsecutiveFailures() >= 3, "连续失败必须计数");
        assertNotNull(cursor.getBlindSpotSince(), "历史缺口必须标记检测盲区（R-AUD-07）");
        boolean unauthForThis = directChangeEvents.findByVerdict("UNAUTHORIZED").stream()
                .anyMatch(e -> "dc-blind.yaml".equals(e.getDataId()));
        assertFalse(unauthForThis, "盲区不得推断无变更、不得误报");
    }

    // ---- AC-22：批量部分成功，只重试失败子项（成功子项永不重发） ----

    @Test
    void batchPartialSuccessRetriesOnlyFailed() {
        Fixture f1 = fixture("batch-ok.yaml");
        Fixture f2 = fixture("batch-fail.yaml");
        String c1 = approvedChange(f1, "k: 1");
        String c2 = approvedChange(f2, "k: 2");
        Batch batch = batchService.createBatch(List.of(c1, c2), "op", null);
        // 让 c2 失败：发布前取消（状态机拒绝 REL-409-001）
        drafts.cancel(c2, "cancel to force batch partial failure", "op", null);

        batchService.runBatch(batch.getBatchId(), "sys-batch",
                (changeNo, sys) -> { releases.release(changeNo, "b-" + changeNo, sys, null); return changeNo; });

        Batch after = batches.findById(batch.getBatchId()).orElseThrow();
        assertEquals("PARTIAL_SUCCESS", after.getStatus());
        assertEquals(1, after.getSucceededCount());
        assertEquals(1, after.getFailedCount());

        List<BatchItem> items = batchItems.findByBatchId(batch.getBatchId());
        BatchItem ok = items.stream().filter(i -> c1.equals(i.getChangeNo())).findFirst().orElseThrow();
        BatchItem failed = items.stream().filter(i -> c2.equals(i.getChangeNo())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED", ok.getStatus());
        assertEquals("FAILED", failed.getStatus());
        long c1ReleasesBefore = releaseRecords.findByChangeNoOrderByStartedAtDesc(c1).size();

        // 只重试失败子项：成功子项不重发（R-SCH-06）
        batchService.retryFailed(batch.getBatchId(), "sys-batch",
                (changeNo, sys) -> { releases.release(changeNo, "b-" + changeNo, sys, null); return changeNo; });

        assertEquals("SUCCEEDED", ok.getStatus(), "成功子项不得被重跑");
        assertEquals(c1ReleasesBefore, releaseRecords.findByChangeNoOrderByStartedAtDesc(c1).size(),
                "成功子项不得产生新发布记录");
    }

    // ---- AC-21：定时到点基线漂移→复核失败转回待发布，不写 Nacos ----

    @Test
    void scheduleRecheckFailureReturnsToPending() throws Exception {
        Fixture f = fixture("sched-return.yaml");
        String changeNo = approvedChange(f, "k: v1");
        ScheduleJob job = scheduleService.schedule(changeNo, Instant.now().plusSeconds(2), "op", null);
        // 到点前基线漂移（外部改写内容）
        ((FakeNacosConfigAdapter) port).seed(f.envId(), f.ref(), "k: drifted");
        Thread.sleep(2500);

        scheduleService.executeDue("sys-sched",
                (cn, sys) -> { releases.release(cn, "sched-" + cn, sys, null); });

        ScheduleJob after = scheduleJobs.findById(job.getJobId()).orElseThrow();
        assertEquals("RETURNED", after.getStatus(), "复核失败必须转回待发布（RETURNED）");
        ChangeRequest cr = changeRequests.findById(changeNo).orElseThrow();
        assertEquals("APPROVED", cr.getStatus(), "变更单回到 APPROVED 待发布");
        long releasesOfChange = releaseRecords.findByChangeNoOrderByStartedAtDesc(changeNo).size();
        assertEquals(0, releasesOfChange, "复核失败不得写 Nacos、不得产生发布记录");
        boolean notified = notifications.findByStatusInOrderByCreatedAtAsc(List.of("QUEUED", "SENDING", "SENT", "RETRYING"))
                .stream().anyMatch(n -> "SCHEDULE_RECHECK_FAILED".equals(n.getEventType()));
        assertTrue(notified, "复核失败必须告警");
    }

    // ---- AC-19 边界：企微持续失败→退避重试 3 次→DEAD + NOTIFY_DEAD 审计 ----

    @Test
    void wecomDeadLetterAfterThreeRetries() {
        Fixture f = fixture("notify-dead.yaml");
        tx.executeWithoutResult(s -> outbox.enqueue(
                com.nacos.ncg.notification.app.NotificationOutbox.EventType.DIRECT_CHANGE_UNAUTHORIZED,
                "CHANGE", f.mapping().getMappingId(), java.util.Map.of("dataId", "notify-dead.yaml"), "WECOM"));
        Notification n = notifications.findByStatusInOrderByCreatedAtAsc(List.of("QUEUED")).stream()
                .filter(x -> "WECOM".equals(x.getChannel())).findFirst().orElseThrow();
        // 未配置企微 webhook → 每次发送必败；快进退避窗口驱动重试
        for (int i = 0; i < 4; i++) {
            n.setNextRetryAt(Instant.now().minusSeconds(5));
            notifications.save(n);
            dispatcher.dispatchDue(10);
            n = notifications.findById(n.getNotificationId()).orElseThrow();
        }
        assertEquals("DEAD", n.getStatus(), "3 次重试后必须进死信（R-NTF-04）");
        assertEquals(4, n.getRetryCount());
        boolean audited = auditEvents.findByChainDateOrderBySeqAsc(java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                .stream().anyMatch(a -> "NOTIFY_DEAD".equals(a.getAction()));
        assertTrue(audited, "死信必须写 NOTIFY_DEAD 审计");
    }

    // ---- AC-16 边界：同名重建后恢复→冲突 RB_409_001（覆盖需重审批或放弃） ----

    @Test
    void restoreConflictWhenSameNameReplaced() {
        Fixture f = fixture("restore-clash.yaml");
        rollbackService.softDelete(f.mapping().getMappingId(), "owner-1", null);
        // 软删墓碑释放名字 → 同名重建（部分唯一索引允许）
        ConfigMapping repl = new ConfigMapping();
        repl.setMappingId(Ulid.generate());
        repl.setAppId(f.app().getAppId());
        repl.setEnvId(f.env().getEnvId());
        repl.setNamespace("public");
        repl.setGroupName("DEFAULT_GROUP");
        repl.setDataId("restore-clash.yaml");
        repl.setSensitive(false);
        repl.setOwnerId("owner-1");
        repl.setStatus("MANAGED");
        repl.setCreatedAt(Instant.now());
        repl.setUpdatedAt(Instant.now());
        mappings.save(repl);

        var item = recycleBin.findByMappingId(f.mapping().getMappingId()).orElseThrow();
        var ex = assertThrows(com.nacos.ncg.shared.NcgException.class,
                () -> rollbackService.restore(item.getItemId(), "owner-1", null));
        assertEquals("RB-409-001", ex.errorCode().code(), "同名活跃映射必须阻断恢复（AC-16）");
    }

    // ---- AC-20 边界：同步只生成目标环境变更单，绝不直写目标 Nacos ----

    @Test
    void syncProposeCreatesTargetChange() {
        Fixture f = fixture("sync-me.yaml");
        // 源环境 Nacos 存在同 ref 内容、目标侧缺失 → 差异项
        EnvId srcEnvId = new EnvId("src" + Ulid.generate().substring(0, 6));
        ((FakeNacosConfigAdapter) port).seedExternalChange(srcEnvId, f.ref(), "src: 1", "op");

        var snap = envSyncService.compare(srcEnvId.value(), f.env().getEnvCode(),
                f.app().getAppId(), f.env().getEnvId(), "alice", null);
        List<String> created = envSyncService.propose(snap.getSnapshotId(), f.app().getAppId(),
                f.env().getEnvId(), "alice", null);

        assertEquals(1, created.size(), "同步必须为目标环境生成变更单（R-SYN-01）");
        ChangeRequest cr = changeRequests.findById(created.get(0)).orElseThrow();
        assertEquals("SYNC", cr.getChangeType());
        assertEquals("DRAFT", cr.getStatus(), "同步只生成草稿，绝不直写目标 Nacos（R-SYN-02）");
    }

    // ---- AC-23 只读降级：实例不可用→该环境写接口 SYS-423-001；恢复可写不补发 ----

    @Test
    void readOnlyDegradationBlocksEnvWrites() {
        Fixture f = fixture("degrade-env.yaml");
        instanceService.register(f.env().getEnvId(), "http://localhost:8848",
                "cred".getBytes(java.nio.charset.StandardCharsets.UTF_8), "owner-1", null);
        instanceService.recordProbe(f.env().getEnvId(), false, -1, null);
        assertEquals("DEGRADED", instances.findByEnvId(f.env().getEnvId()).orElseThrow().getStatus(),
                "单次探活失败应为 DEGRADED");
        instanceService.recordProbe(f.env().getEnvId(), false, -1, null);
        instanceService.recordProbe(f.env().getEnvId(), false, -1, null);
        assertEquals("UNAVAILABLE", instances.findByEnvId(f.env().getEnvId()).orElseThrow().getStatus(),
                "连续 3 次失败应为 UNAVAILABLE（R-CON-05）");
        var ex = assertThrows(com.nacos.ncg.shared.NcgException.class, () -> drafts.open(
                f.app().getAppId(), f.env().getEnvId(), f.mapping().getMappingId(),
                "UPDATE", "blocked", "degraded env write attempt", "k: 2", "owner-1",
                com.nacos.ncg.shared.RiskLevel.LOW, null));
        assertEquals("SYS-423-001", ex.errorCode().code(), "只读降级必须返回 SYS-423-001（设计 §13）");
        instanceService.recordProbe(f.env().getEnvId(), true, 5, null);
        assertEquals("ONLINE", instances.findByEnvId(f.env().getEnvId()).orElseThrow().getStatus());
        // 恢复仅恢复可写能力，不自动补发历史操作（R-CON-05）
        drafts.open(f.app().getAppId(), f.env().getEnvId(), f.mapping().getMappingId(),
                "UPDATE", "after recovery", "env write after recovery ok", "k: 3", "owner-1",
                com.nacos.ncg.shared.RiskLevel.LOW, null);
    }

    // ---- R-AUTH-05 授权到期即失效 ----

    @Test
    void grantExpiryEnforced() {
        Fixture f = fixture("grant-exp.yaml");
        var a1 = accountService.create("exp-user-1", "E1", "password-12345", false, "owner-1", null);
        var a2 = accountService.create("exp-user-2", "E2", "password-12345", false, "owner-1", null);
        grants.grant("USER", a1.getAccountId(), f.app().getAppId(), f.env().getEnvId(),
                com.nacos.ncg.shared.PermissionPoint.VIEW,
                java.time.Instant.now().plusSeconds(3600), "owner-1", null);
        grants.grant("USER", a2.getAccountId(), f.app().getAppId(), f.env().getEnvId(),
                com.nacos.ncg.shared.PermissionPoint.VIEW,
                java.time.Instant.now().minusSeconds(60), "owner-1", null);
        assertTrue(grants.hasPermission(a1.getAccountId(), f.app().getAppId(), f.env().getEnvId(),
                com.nacos.ncg.shared.PermissionPoint.VIEW));
        assertFalse(grants.hasPermission(a2.getAccountId(), f.app().getAppId(), f.env().getEnvId(),
                com.nacos.ncg.shared.PermissionPoint.VIEW), "过期授权必须立即失效（R-AUTH-05）");
    }

    // ---- AC-18 权限拒绝分支：无 VIEW_SENSITIVE→AUTHZ-403_001 ----

    @Test
    void sensitiveViewDeniedWithoutPermission() {
        Fixture f = fixture("sens-deny.yaml");
        accountService.create("plain-user", "P", "password-12345", false, "owner-1", null);
        var ex = assertThrows(com.nacos.ncg.shared.NcgException.class,
                () -> mappingAdminController.sensitiveView(f.mapping().getMappingId(),
                        new com.nacos.ncg.web.MappingAdminController.SensitiveViewRequest(
                                "need to inspect the value now"),
                        () -> "plain-user"));
        assertEquals("AUTHZ-403-001", ex.errorCode().code(), "无 VIEW_SENSITIVE 必须拒绝（AC-18）");
    }

    // ---- AC-17 + R-REL-07：影响面冻结真实订阅者 + 发布订阅差异告警 ----

    @Test
    void impactFreezesSubscribersAndDiffAlertedOnRelease() {
        Fixture f = fixture("impact-sub.yaml");
        var fake = (FakeNacosConfigAdapter) port;
        var subA = new com.nacos.ncg.adapter.nacos.port.Subscriber("10.0.0.1:8080", "app-a");
        var subB = new com.nacos.ncg.adapter.nacos.port.Subscriber("10.0.0.2:8080", "app-b");
        fake.seed(f.envId(), f.ref(), "key: v0");
        fake.setSubscribers(f.envId(), f.ref(), List.of(subA, subB));

        // ① AC-17：提交冻结真实订阅者影响面（实例数+地址）
        ChangeRequest cr1 = drafts.open(f.app().getAppId(), f.env().getEnvId(),
                f.mapping().getMappingId(), "UPDATE", "impact", "impact snapshot with subscribers",
                "key: v1", "alice", com.nacos.ncg.shared.RiskLevel.LOW, null);
        drafts.submit(cr1.getChangeNo(), "alice", com.nacos.ncg.shared.RiskLevel.LOW, null);
        String impact = changeRequests.findById(cr1.getChangeNo()).orElseThrow().getImpactSnapshot().json();
        assertTrue(impact.contains("10.0.0.1:8080") && impact.contains("10.0.0.2:8080"),
                "影响面必须冻结真实订阅者地址（AC-17）");
        assertTrue(impact.replace(" ", "").contains("\"subscriberCount\":2"), "影响面必须记录订阅实例数（AC-17）");
        approvals.decide(cr1.getChangeNo(), "APPROVE", null, null, "owner-1", null);
        releases.release(cr1.getChangeNo(), "v1", "alice", null);

        // ② R-REL-07：提交后订阅者减少→发布结果审计记录差异，不得静默
        ChangeRequest cr2 = drafts.open(f.app().getAppId(), f.env().getEnvId(),
                f.mapping().getMappingId(), "UPDATE", "impact2", "second change for subscriber diff",
                "key: v2", "alice", com.nacos.ncg.shared.RiskLevel.LOW, null);
        drafts.submit(cr2.getChangeNo(), "alice", com.nacos.ncg.shared.RiskLevel.LOW, null);
        approvals.decide(cr2.getChangeNo(), "APPROVE", null, null, "owner-1", null);
        fake.setSubscribers(f.envId(), f.ref(), List.of(subA)); // B 下线
        releases.release(cr2.getChangeNo(), "v2", "alice", null);
        String rr = auditEvents.findByChangeNoOrderBySeqDesc(cr2.getChangeNo()).stream()
                .filter(a -> "RELEASE_RESULT".equals(a.getAction()))
                .findFirst().orElseThrow().getAfterMasked().json();
        assertTrue(rr.contains("10.0.0.2:8080"),
                "订阅者消失必须写入发布结果审计差异（R-REL-07，不得静默）");
    }
}

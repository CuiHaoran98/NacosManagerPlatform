package com.nacos.ncg.worker.app;

import com.nacos.ncg.audit.app.AuditChainService;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.audit.app.DirectScanService;
import com.nacos.ncg.batch.app.BatchService;
import com.nacos.ncg.batch.domain.Batch;
import com.nacos.ncg.batch.repo.BatchRepository;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.catalog.repo.EnvRepository;
import com.nacos.ncg.catalog.app.InstanceService;
import com.nacos.ncg.notification.app.NotificationDispatcher;
import com.nacos.ncg.notification.app.NotificationOutbox;
import com.nacos.ncg.release.app.ReleaseService;
import com.nacos.ncg.scheduler.app.ScheduleService;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.worker.repo.WorkerLeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Worker 单活任务组（§12 / ch12）：所有任务先抢 worker:active 租约（TTL 30s），
 * 崩溃后热备经租约过期接管；每个任务幂等（成功子项不重发、同一历史只报警一次）。
 * 7 任务：定时发布/批量执行/直连扫描/通知派发/回收站清理/审计链校验/实例探活。
 */
@Component
// 任务组只属于 Worker 应用（§3.4 web 无状态）；web 不应跑调度——设计漂移收口
@ConditionalOnProperty(prefix = "ncg", name = "worker.enabled", havingValue = "true", matchIfMissing = false)
public class WorkerTasks {

    private static final Logger log = LoggerFactory.getLogger(WorkerTasks.class);

    private final LeaseManager leaseManager;
    private final ScheduleService scheduleService;
    private final BatchService batchService;
    private final BatchRepository batches;
    private final ReleaseService releaseService;
    private final DirectScanService directScanService;
    private final NotificationDispatcher dispatcher;
    private final NotificationOutbox outbox;
    private final AuditChainService auditChainService;
    private final InstanceService instanceService;
    private final NacosConfigPort port;
    private final EnvRepository envs;
    private final WorkerLeaseRepository leases;
    private final AuditWriter auditWriter;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public WorkerTasks(LeaseManager leaseManager, ScheduleService scheduleService,
                       BatchService batchService, BatchRepository batches,
                       ReleaseService releaseService, DirectScanService directScanService,
                       NotificationDispatcher dispatcher, NotificationOutbox outbox,
                       AuditChainService auditChainService, InstanceService instanceService,
                       NacosConfigPort port, EnvRepository envs,
                       WorkerLeaseRepository leases, AuditWriter auditWriter,
                       org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.leaseManager = leaseManager;
        this.scheduleService = scheduleService;
        this.batchService = batchService;
        this.batches = batches;
        this.releaseService = releaseService;
        this.directScanService = directScanService;
        this.dispatcher = dispatcher;
        this.outbox = outbox;
        this.auditChainService = auditChainService;
        this.instanceService = instanceService;
        this.port = port;
        this.envs = envs;
        this.leases = leases;
        this.auditWriter = auditWriter;
        this.jdbc = jdbc;
    }

    /** 定时发布（每 10s 兜底轮询；事件驱动由 schedule 触发）。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void runScheduledRelease() {
        if (!hold()) return;
        scheduleService.executeDue("worker-scheduler", (changeNo, systemActor) -> {
            releaseService.release(changeNo, "sched-" + changeNo, systemActor, null, null, "SCHEDULED", systemActor);
        });
    }

    /** 批量执行（每 10s）。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void runBatchExecution() {
        if (!hold()) return;
        for (Batch b : batches.findAll()) {
            if ("CREATED".equals(b.getStatus()) || "RUNNING".equals(b.getStatus())) {
                batchService.runBatch(b.getBatchId(), "worker-batch",
                        (changeNo, systemActor) -> {
                            releaseService.release(changeNo, "batch-" + b.getBatchId(), systemActor, null, null, "BATCH", systemActor);
                            return changeNo;
                        });
            }
        }
    }

    /** 直连扫描（每 5 分钟，R-AUD-04）。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void runDirectScan() {
        if (!hold()) return;
        for (Env env : envs.findAll()) {
            try {
                directScanService.scan(env.getEnvId(), env.getEnvCode());
            } catch (RuntimeException ex) {
                log.warn("direct scan failed for {}: {}", env.getEnvCode(), ex.getMessage());
            }
        }
    }

    /** 通知派发（每 10s）。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 25_000)
    public void runNotificationDispatch() {
        if (!hold()) return;
        dispatcher.dispatchDue(50);
    }

    /** 回收站清理（每日 03:30，purge_at 过期即物理删除并审计 R-RB-04）。 */
    @Scheduled(cron = "0 30 3 * * *")
    public void runRecycleBinCleanup() {
        if (!hold()) return;
        var rows = jdbc.queryForList(
                "SELECT item_id FROM ncg_recycle_bin WHERE purge_at < now()", String.class);
        for (String itemId : rows) {
            jdbc.update("DELETE FROM ncg_recycle_bin WHERE item_id = ?", itemId);
            auditWriter.append(AuditWriter.Command.system(AuditAction.RECYCLE_PURGE, "RECYCLE_BIN", itemId, null));
        }
    }

    /** 审计链校验（每日 04:00，断链告警不修复）。 */
    @Scheduled(cron = "0 0 4 * * *")
    public void runAuditChainVerify() {
        if (!hold()) return;
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        AuditChainService.Report report = auditChainService.verify(yesterday);
        if (!report.ok()) {
            outbox.enqueue(NotificationOutbox.EventType.AUDIT_CHAIN_BROKEN,
                    "AUDIT_CHAIN", yesterday.toString(),
                    Map.of("brokenAtSeq", String.valueOf(report.brokenAtSeq())),
                    "WECOM");
        }
    }

    /** 实例探活（每 60s，R-CON-04）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    public void runInstanceProbe() {
        if (!hold()) return;
        for (Env env : envs.findAll()) {
            try {
                var report = port.test(new EnvId(env.getEnvCode()));
                instanceService.recordProbe(env.getEnvId(), report.reachable() && report.authenticated(),
                        report.latencyMs(), null);
            } catch (RuntimeException ex) {
                instanceService.recordProbe(env.getEnvId(), false, -1, null);
            }
        }
    }

    private boolean hold() {
        return leaseManager.hold(LeaseManager.WORKER_ACTIVE, "worker-1");
    }
}

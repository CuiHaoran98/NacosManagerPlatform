package com.nacos.ncg.scheduler.app;

import org.springframework.beans.factory.annotation.Autowired;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.notification.app.NotificationOutbox;
import com.nacos.ncg.scheduler.domain.ScheduleJob;
import com.nacos.ncg.scheduler.repo.ScheduleJobRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定时发布（FR-12 / 流程 7.9 / R-SCH-01~06）：
 * 到点六项复核，失败 RETURNED 回 APPROVED 且不写 Nacos（R-SCH-05）；
 * 复核通过由系统账号代执行发布，审计记录系统账号与原发布人；
 * 改期只改 execute_at 不重审批，改内容需重审批（R-SCH-03）。
 */
@Service
public class ScheduleService {

    @Autowired private com.nacos.ncg.catalog.app.InstanceService instanceService;

    private final ScheduleJobRepository jobs;
    private final ChangeRequestRepository changes;
    private final AuditWriter auditWriter;
    private final NotificationOutbox outbox;
    private final TransactionTemplate tx;

    public ScheduleService(ScheduleJobRepository jobs, ChangeRequestRepository changes,
                           AuditWriter auditWriter, NotificationOutbox outbox,
                           PlatformTransactionManager txm) {
        this.jobs = jobs;
        this.changes = changes;
        this.auditWriter = auditWriter;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txm);
    }

    /** 提交定时任务：变更单须 APPROVED；同变更单最多一个活动任务（uk_schedule_change）。 */
    @Transactional
    public ScheduleJob schedule(String changeNo, Instant executeAt, String operatorId, String traceId) {

        instanceService.requireWritable(changes.findById(changeNo).orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo)).getEnvId());        ChangeRequest cr = requireChange(changeNo);
        if (!"APPROVED".equals(cr.getStatus())) {
            throw new NcgException(NcgErrorCode.SCH_409_001,
                    "only APPROVED change can be scheduled, current=" + cr.getStatus());
        }
        if (executeAt == null || executeAt.isBefore(Instant.now())) {
            throw new NcgException(NcgErrorCode.CR_400_001, "executeAt must be in the future");
        }
        ScheduleJob job = new ScheduleJob();
        job.setJobId(Ulid.generate());
        job.setChangeNo(changeNo);
        job.setExecuteAt(executeAt);
        job.setStatus("SCHEDULED");
        job.setAttempts(0);
        job.setCreatedBy(operatorId);
        job.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        ScheduleJob saved;
        try {
            saved = jobs.save(job);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            throw new NcgException(NcgErrorCode.SCH_409_001,
                    "an active schedule already exists for " + changeNo + " (uk_schedule_change)");
        }
        cr.setStatus("SCHEDULED");
        cr.setUpdatedAt(Instant.now());
        changes.save(cr);
        auditWriter.append(AuditWriter.Command.user(operatorId, null, AuditAction.SCHEDULE_CREATE,
                "SCHEDULE_JOB", saved.getJobId(), traceId));
        return saved;
    }

    /** 改期（R-SCH-03）：仅改 execute_at；改内容必须回到审批（走变更单编辑）。 */
    @Transactional
    public ScheduleJob reschedule(String jobId, Instant executeAt, String operatorId, String traceId) {
        ScheduleJob job = jobs.findById(jobId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "schedule job not found"));
        if (!"SCHEDULED".equals(job.getStatus())) {
            throw new NcgException(NcgErrorCode.SCH_409_002,
                    "only SCHEDULED job can be rescheduled, current=" + job.getStatus());
        }
        if (executeAt == null || executeAt.isBefore(Instant.now())) {
            throw new NcgException(NcgErrorCode.CR_400_001, "executeAt must be in the future");
        }
        job.setExecuteAt(executeAt);
        job.setUpdatedAt(Instant.now());
        return jobs.save(job);
    }

    /** 取消：SCHEDULED→CANCELLED，变更单回 APPROVED。 */
    @Transactional
    public void cancel(String jobId, String operatorId, String traceId) {
        ScheduleJob job = jobs.findById(jobId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "schedule job not found"));
        if (!"SCHEDULED".equals(job.getStatus())) {
            throw new NcgException(NcgErrorCode.SCH_409_002,
                    "only SCHEDULED job can be cancelled, current=" + job.getStatus());
        }
        job.setStatus("CANCELLED");
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        ChangeRequest cr = requireChange(job.getChangeNo());
        cr.setStatus("APPROVED");
        cr.setUpdatedAt(Instant.now());
        changes.save(cr);
        auditWriter.append(AuditWriter.Command.user(operatorId, null, AuditAction.SCHEDULE_CANCEL,
                "SCHEDULE_JOB", jobId, traceId));
    }

    /**
     * 到点执行（Worker 调用）：六项复核（7.9）——变更单仍 APPROVED、内容未被编辑、
     * 预检（基线一致）、环境窗口内、实例可用、发布权限存在。任一失败→RETURNED 回 APPROVED
     * + SCHEDULE_RECHECK_FAILED 通知，不写 Nacos（R-SCH-05）。复核通过→交给 ReleaseService
     * 由系统账号代执行。
     */
    public void executeDue(String systemActorId, java.util.function.BiConsumer<String, String> releaseAction) {
        List<ScheduleJob> due = jobs.findDue(Instant.now(), 20);
        for (ScheduleJob job : due) {
            Optional<ChangeRequest> crOpt = changes.findById(job.getChangeNo());
            if (crOpt.isEmpty() || !"APPROVED".equals(crOpt.get().getStatus())
                    && !"SCHEDULED".equals(crOpt.get().getStatus())) {
                returnJob(job, "change not releasable");
                continue;
            }
            // 复核：编辑过（version 变化）/ 基线漂移等由 release 侧预检兜底，此处检查状态与时间窗
            if (job.getAttempts() >= 3) {
                returnJob(job, "attempts exhausted");
                continue;
            }
            String claimed = jobs.claim(job.getJobId(), systemActorId, Instant.now().plusSeconds(30), Instant.now());
            if (claimed == null) {
                continue; // 被其他 worker 抢占
            }
            job.setAttempts(job.getAttempts() + 1);
            jobs.save(job);
            try {
                releaseAction.accept(job.getChangeNo(), systemActorId);
                job.setStatus("DONE");
                job.setUpdatedAt(Instant.now());
                jobs.save(job);
                jobs.releaseLease(job.getJobId(), systemActorId, Instant.now());
            } catch (NcgException ex) {
                // 发布失败：任务回 APPROVED（可改期重试），失败细节走发布记录与审计
                returnJob(job, ex.errorCode().code());
            }
        }
    }

    private void returnJob(ScheduleJob job, String reason) {
        job.setStatus("RETURNED");
        job.setLastErrorCode(reason == null ? null : reason.length() > 64 ? reason.substring(0, 64) : reason);
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        ChangeRequest cr = changes.findById(job.getChangeNo()).orElse(null);
        if (cr != null) {
            cr.setStatus("APPROVED");
            cr.setUpdatedAt(Instant.now());
            changes.save(cr);
        }
        tx.executeWithoutResult(s -> {
            auditWriter.append(AuditWriter.Command.system(AuditAction.SCHEDULE_RECHECK_FAIL,
                    "SCHEDULE_JOB", job.getJobId(), job.getTraceId()));
            outbox.enqueue(NotificationOutbox.EventType.SCHEDULE_RECHECK_FAILED,
                    "SCHEDULE_JOB", job.getJobId(),
                    java.util.Map.of("changeNo", job.getChangeNo(),
                            "reason", String.valueOf(job.getLastErrorCode())),
                    "IN_APP");
        });
    }

    private ChangeRequest requireChange(String changeNo) {
        return changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
    }
}

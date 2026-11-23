package com.nacos.ncg.web;

import com.nacos.ncg.scheduler.app.ScheduleService;
import com.nacos.ncg.scheduler.domain.ScheduleJob;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.security.Principal;
import java.util.Map;

/**
 * 定时发布 REST（FR-12）：提交/改期/取消。
 * 契约 #24/#25：body {plannedAt}（后端原 executeAt 保留为别名）；
 * 响应用 ScheduleJob 行形状 {jobId,changeNo,plannedAt,status,reviewReason}（原字段保留）。
 * 差异2 决策：不新增 GET 定时列表端点（契约未定义）；前端以
 * listChanges(status=SCHEDULED)+store 推导，字段命名统一为 plannedAt。
 */
@RestController
@RequestMapping("/api/v1")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final com.nacos.ncg.scheduler.repo.ScheduleJobRepository jobs;

    public ScheduleController(ScheduleService scheduleService,
                              com.nacos.ncg.scheduler.repo.ScheduleJobRepository jobs) {
        this.scheduleService = scheduleService;
        this.jobs = jobs;
    }

    /** plannedAt=契约字段；executeAt=兼容别名（两者同给以 plannedAt 为准）。 */
    public record ScheduleRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant plannedAt,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant executeAt) {
        public Instant at() {
            return plannedAt != null ? plannedAt : executeAt;
        }
    }

    @PostMapping("/changes/{changeNo}/schedule")
    public Map<String, Object> schedule(@PathVariable String changeNo, @RequestBody ScheduleRequest req,
                                        Principal principal) {
        Instant at = req.at();
        if (at == null) {
            throw new com.nacos.ncg.shared.NcgException(
                    com.nacos.ncg.shared.NcgErrorCode.CR_400_001, "plannedAt is required");
        }
        ScheduleJob job = scheduleService.schedule(changeNo, at, principal.getName(), null);
        return ContractViews.scheduleRow(job);
    }

    @PatchMapping("/schedule/{jobId}")
    public Map<String, Object> reschedule(@PathVariable String jobId, @RequestBody ScheduleRequest req,
                                          Principal principal) {
        Instant at = req.at();
        if (at == null) {
            throw new com.nacos.ncg.shared.NcgException(
                    com.nacos.ncg.shared.NcgErrorCode.CR_400_001, "plannedAt is required");
        }
        ScheduleJob job = scheduleService.reschedule(jobId, at, principal.getName(), null);
        return ContractViews.scheduleRow(job);
    }

    @PostMapping("/schedule/{jobId}/cancel")
    public void cancel(@PathVariable String jobId, Principal principal) {
        scheduleService.cancel(jobId, principal.getName(), null);
    }

    /** 取消定时（§6.2 DELETE /changes/{changeNo}/schedule，SCH-409-001）。 */
    @DeleteMapping("/changes/{changeNo}/schedule")
    public Map<String, Object> cancelByChange(@PathVariable String changeNo, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        ScheduleJob job = jobs.findByChangeNo(changeNo)
                .orElseThrow(() -> new com.nacos.ncg.shared.NcgException(
                        com.nacos.ncg.shared.NcgErrorCode.SCH_409_001, "no schedule job for change: " + changeNo));
        scheduleService.cancel(job.getJobId(), actorId, null);
        return ContractViews.scheduleRow(job);
    }

}

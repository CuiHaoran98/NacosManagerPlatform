package com.nacos.ncg.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Instance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约响应整形（t6 联调，doc/ncg-api-contract.md §3）：
 * 后端域对象字段名与契约 DTO 不一致处统一在此补齐别名（向后兼容加法，不删旧字段）。
 */
final class ContractViews {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ContractViews() {
    }

    /** 映射行：契约 ConfigMapping{mappingId,appId,envId,dataId,format,md5,collectedAt} + 旧字段全保留。 */
    static Map<String, Object> mappingRow(ConfigMapping m) {
        Map<String, Object> row = MAPPER.convertValue(m, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
        });
        row.putIfAbsent("format", m.getFormat());
        row.put("md5", m.getLastSnapshotMd5());
        row.put("collectedAt", m.getLastCollectedAt());
        return row;
    }

    /** 实例行：契约 InstanceInfo{instanceId,envId,endpoint,health,lastCheckedAt} + 旧字段保留。 */
    static Map<String, Object> instanceRow(Instance in) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("instanceId", in.getInstanceId());
        row.put("envId", in.getEnvId());
        row.put("endpoint", in.getEndpoint());
        row.put("health", healthOf(in.getStatus()));
        row.put("lastCheckedAt", in.getLastProbeAt());
        row.put("status", in.getStatus()); // 旧字段
        row.put("latencyMs", in.getLastProbeLatencyMs());
        return row;
    }

    /** ONLINE→UP / UNAVAILABLE→DOWN / DEGRADED→DEGRADED（契约 InstanceInfo.health 口径）。 */
    static String healthOf(String status) {
        if (status == null) {
            return "DOWN";
        }
        return switch (status) {
            case "ONLINE" -> "UP";
            case "UNAVAILABLE" -> "DOWN";
            default -> "DEGRADED";
        };
    }

    /**
     * 审计行（契约 AuditEvent + 全字段保留供详情弹窗）：
     * eventType←action、actor←actorId、target←objectType+"/"+objectId、createdAt←occurredAt、hash←eventHash。
     */
    static Map<String, Object> auditRow(com.nacos.ncg.audit.domain.AuditEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", e.getEventId());
        row.put("eventType", e.getAction());
        row.put("actor", e.getActorId());
        String target = e.getObjectId() == null ? e.getObjectType()
                : (e.getObjectType() == null ? e.getObjectId() : e.getObjectType() + "/" + e.getObjectId());
        row.put("target", target);
        row.put("traceId", e.getTraceId());
        row.put("createdAt", e.getOccurredAt());
        row.put("hash", e.getEventHash());
        row.put("prevHash", e.getPrevHash());
        // 全字段（详情弹窗）
        row.put("chainDate", e.getChainDate());
        row.put("seq", e.getSeq());
        row.put("occurredAt", e.getOccurredAt());
        row.put("actorType", e.getActorType());
        row.put("actorId", e.getActorId());
        row.put("sourceIp", e.getSourceIp());
        row.put("action", e.getAction());
        row.put("objectType", e.getObjectType());
        row.put("objectId", e.getObjectId());
        row.put("beforeMasked", e.getBeforeMasked());
        row.put("afterMasked", e.getAfterMasked());
        row.put("result", e.getResult());
        row.put("errorCode", e.getErrorCode());
        row.put("changeNo", e.getChangeNo());
        row.put("eventHash", e.getEventHash());
        row.put("exportRef", e.getExportRef());
        return row;
    }

    /** 直连事件行（契约 DirectChangeEvent：id/instanceId/dataId/detectedAt/matched/status + 原字段）。 */
    static Map<String, Object> directChangeRow(com.nacos.ncg.audit.domain.DirectChangeEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getEventId());
        row.put("instanceId", e.getEnvId());
        row.put("dataId", e.getDataId());
        row.put("detectedAt", e.getDetectedAt());
        row.put("matched", "MATCHED".equals(e.getVerdict()));
        row.put("status", e.getClosedAt() == null ? "OPEN" : "CLOSED");
        row.put("eventId", e.getEventId());
        row.put("envId", e.getEnvId());
        row.put("nacosHistoryId", e.getNacosHistoryId());
        row.put("matchReleaseId", e.getMatchReleaseId());
        row.put("verdict", e.getVerdict());
        row.put("closedAt", e.getClosedAt());
        row.put("createdAt", e.getCreatedAt());
        return row;
    }

    /** 发布记录行（契约 ReleaseRecord：result←outcome 小写、releasedBy←operatorId、releasedAt←finishedAt）。 */
    static Map<String, Object> releaseRow(com.nacos.ncg.release.domain.ReleaseRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("releaseId", r.getReleaseId());
        row.put("changeNo", r.getChangeNo());
        row.put("result", r.getOutcome() == null ? null : r.getOutcome().toLowerCase());
        row.put("readbackMd5", r.getReadbackMd5());
        row.put("releasedBy", r.getOperatorId());
        row.put("releasedAt", r.getFinishedAt());
        // 原字段保留（联调与详情用）
        row.put("releaseKey", r.getReleaseKey());
        row.put("targetVersion", r.getTargetVersion());
        row.put("outcome", r.getOutcome());
        row.put("rejectReason", r.getRejectReason());
        row.put("expectedMd5", r.getExpectedMd5());
        row.put("errorCode", r.getErrorCode());
        row.put("startedAt", r.getStartedAt());
        row.put("finishedAt", r.getFinishedAt());
        return row;
    }

    /** 定时任务行（契约 ScheduleJob：plannedAt←executeAt 别名、reviewReason←lastErrorCode）。 */
    static Map<String, Object> scheduleRow(com.nacos.ncg.scheduler.domain.ScheduleJob job) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jobId", job.getJobId());
        row.put("changeNo", job.getChangeNo());
        row.put("plannedAt", job.getExecuteAt());
        row.put("reviewReason", job.getLastErrorCode());
        row.put("status", scheduleStatusOf(job.getStatus()));
        row.put("executeAt", job.getExecuteAt());
        row.put("rawStatus", job.getStatus());
        row.put("lastErrorCode", job.getLastErrorCode());
        row.put("attempts", job.getAttempts());
        row.put("createdAt", job.getCreatedAt());
        row.put("updatedAt", job.getUpdatedAt());
        return row;
    }

    /** SCHEDULED→ACTIVE / CANCELLED→CANCELED / REVIEW_FAILED|EXECUTED 原样（契约 ScheduleJob.status 口径）。 */
    static String scheduleStatusOf(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "SCHEDULED" -> "ACTIVE";
            case "CANCELLED" -> "CANCELED";
            default -> status;
        };
    }

    /** 通知绑定行（契约 NotifyBinding：target 恒脱敏 ●●●●●●●●，MUST-NOT-05 不回显真值）。 */
    static Map<String, Object> notifyRow(com.nacos.ncg.notification.domain.NotifyBinding b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appId", b.getAppId());
        row.put("envId", b.getEnvId());
        row.put("channel", b.getChannel());
        row.put("target", "●●●●●●●●");
        row.put("updatedAt", b.getUpdatedAt());
        row.put("bindingId", b.getBindingId());
        row.put("status", b.getStatus());
        row.put("hasTarget", b.getTargetCiphertext() != null && b.getTargetCiphertext().length > 0);
        row.put("createdAt", b.getCreatedAt());
        return row;
    }

    /** ChangeView record → map（record 无 toMap，统一走 Jackson 转换）。 */
    static Map<String, Object> changeViewRow(ChangeView v) {
        return MAPPER.convertValue(v, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** 应用行（契约 AppItem：appName←name、owner←ownerId、envIds=该应用配置覆盖的环境）。 */
    static Map<String, Object> appRow(com.nacos.ncg.catalog.domain.App app, List<String> envIds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appId", app.getAppId());
        row.put("appName", app.getName());
        row.put("owner", app.getOwnerId());
        row.put("envIds", envIds);
        row.put("appCode", app.getAppCode());
        row.put("ownerId", app.getOwnerId());
        row.put("name", app.getName());
        row.put("description", app.getDescription());
        row.put("status", app.getStatus());
        return row;
    }

    /** 账号行（契约 UserAccount / CurrentUser 公共形状：userId←accountId、username←loginName、roles、enabled）。 */
    static Map<String, Object> userRow(com.nacos.ncg.auth.domain.Account a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", a.getAccountId());
        row.put("username", a.getLoginName());
        row.put("displayName", a.getDisplayName());
        row.put("roles", a.isAdmin() ? List.of("ADMIN") : List.of("USER"));
        row.put("enabled", !("DISABLED".equals(a.getStatus()) || "LOCKED".equals(a.getStatus())));
        row.put("createdAt", a.getCreatedAt());
        row.put("accountId", a.getAccountId());
        row.put("loginName", a.getLoginName());
        row.put("status", a.getStatus());
        row.put("isAdmin", a.isAdmin());
        return row;
    }

    /** 权限点：管理员=全部 10 点；其余=其 USER 授权并集（授权点驱动前端渲染）。 */
    static java.util.List<String> permissionsOf(com.nacos.ncg.auth.domain.Account a,
                                                com.nacos.ncg.auth.repo.GrantRepository grantRepository) {
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (a.isAdmin()) {
            for (com.nacos.ncg.shared.PermissionPoint p : com.nacos.ncg.shared.PermissionPoint.values()) {
                perms.add(p.name());
            }
            return perms;
        }
        for (com.nacos.ncg.auth.domain.Grant g : grantRepository.findBySubjectTypeAndSubjectId("USER", a.getAccountId())) {
            if (g.getPermission() != null) {
                String p = String.valueOf(g.getPermission());
                if (!perms.contains(p)) perms.add(p);
            }
        }
        return perms;
    }

    /** 授权行（契约 Grant：userId←subjectId、username=账号名、grantedBy/grantedAt 别名）。 */
    static Map<String, Object> grantRow(com.nacos.ncg.auth.domain.Grant g, String username) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("grantId", g.getGrantId());
        row.put("userId", g.getSubjectId());
        row.put("username", username);
        row.put("appId", g.getAppId());
        row.put("envId", g.getEnvId());
        row.put("permission", g.getPermission() == null ? null : String.valueOf(g.getPermission()));
        row.put("grantedBy", g.getGrantedBy());
        row.put("grantedAt", g.getCreatedAt());
        row.put("subjectType", g.getSubjectType());
        row.put("subjectId", g.getSubjectId());
        row.put("expiresAt", g.getExpiresAt());
        return row;
    }
}

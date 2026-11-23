package com.nacos.ncg.web;

import com.nacos.ncg.change.domain.ChangeRequest;

import java.time.Instant;

/** 变更单视图（§6.2）：不暴露密文列，只回摘要与状态。 */
public record ChangeView(
        String changeNo, String appId, String envId, String mappingId, String changeType,
        String title, String reason, String contentSha256, String baselineMd5,
        String riskLevel, Object riskFactors, String status,
        String submitterId, String publisherId, Instant scheduleAt,
        Instant createdAt, Instant updatedAt, Instant closedAt) {

    public static ChangeView of(ChangeRequest cr) {
        return new ChangeView(
                cr.getChangeNo(), cr.getAppId(), cr.getEnvId(), cr.getMappingId(), cr.getChangeType(),
                cr.getTitle(), cr.getReason(), cr.getContentSha256(), cr.getBaselineMd5(),
                cr.getRiskLevel(), cr.getRiskFactors() == null ? null : cr.getRiskFactors().json(),
                cr.getStatus(), cr.getSubmitterId(), cr.getPublisherId(), cr.getScheduleAt(),
                cr.getCreatedAt(), cr.getUpdatedAt(), cr.getClosedAt());
    }
}

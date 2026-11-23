package com.nacos.ncg.shared;

/**
 * 权限与职责分离策略（详细设计 v1.1 §4 领域服务 §9 / spec FR-06、R-AUTH-01~05、R-APR-01~03）。
 * 规则（任一不满足即拒绝，fail-closed）：
 * 1) 提交人 ≠ 审批人（R-APR-02）；
 * 2) 发布人 ≠ 审批人，例外：发布人是该应用负责人（R-APR-03）；
 * 3) 高风险变更仅应用负责人（或审计过的代理人 [TBD A-06]）可审批（R-APR-04）；
 * 4) 生产单人会签 1 名审批人即通过，无审批超时（PRD 决策 3/14）。
 */
public final class PermissionPolicy {

    public enum Decision {
        ALLOW,
        DENY_SAME_ACTOR,          // 提交人/发布人不能审批自己发起的单
        DENY_NOT_OWNER_FOR_HIGH,  // 高风险仅负责人（或审计代理人）可批
        DENY_SEPARATION           // 职责分离不满足
    }

    /** 审批职责分离校验。 */
    public Decision canApprove(
            String approverId,
            String submitterId,
            String publisherId,
            boolean highRisk,
            String appOwnerId,
            boolean approverIsAuditedProxy) {
        if (approverId == null || approverId.equals(submitterId)) {
            return Decision.DENY_SAME_ACTOR;
        }
        if (highRisk && !approverId.equals(appOwnerId) && !approverIsAuditedProxy) {
            return Decision.DENY_NOT_OWNER_FOR_HIGH;
        }
        if (publisherId != null && publisherId.equals(approverId) && !approverId.equals(appOwnerId)) {
            return Decision.DENY_SEPARATION;
        }
        return Decision.ALLOW;
    }

    /** 发布职责分离校验：发布人不得是审批人，除非发布人是应用负责人。 */
    public boolean canPublish(String publisherId, String approverId, String appOwnerId) {
        if (publisherId == null) {
            return false;
        }
        return !publisherId.equals(approverId) || publisherId.equals(appOwnerId);
    }
}

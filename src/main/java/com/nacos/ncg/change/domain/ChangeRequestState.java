package com.nacos.ncg.change.domain;

/**
 * 变更单状态机（详细设计 v1.1 §4.2 / spec E.2.1）。
 * 迁移：DRAFT→PENDING_APPROVAL→APPROVED→(SCHEDULED)→RELEASING→SUCCEEDED|FAILED|INDETERMINATE；
 * CANCELLED 仅从 DRAFT/PENDING_APPROVAL/APPROVED/SCHEDULED 进入（发布后不可取消，只能回滚）。
 * 仅 RELEASING 状态允许写 Nacos（唯一写入口 C-ARC-03）；
 * INDETERMINATE 只能经人工对账 reconcile 确认生效→SUCCEEDED / 未生效→FAILED，禁自动重试（C-ARC-05）。
 */
public enum ChangeRequestState {
    /** 草稿：可编辑，未冻结基线，不占用并发配额。 */
    DRAFT,
    /** 待审批：已提交，基线/快照/影响面/风险已冻结。 */
    PENDING_APPROVAL,
    /** 已通过待发布。 */
    APPROVED,
    /** 已计划：定时任务登记，等待 execute_at。 */
    SCHEDULED,
    /** 发布中：唯一允许写 Nacos 的状态。 */
    RELEASING,
    /** 已发布（终态）：写入+读回 MD5 一致。 */
    SUCCEEDED,
    /** 发布失败（终态）：适配器明确拒绝。 */
    FAILED,
    /** 结果不确定：写入已发出但读回失败，高优告警+人工对账。 */
    INDETERMINATE,
    /** 已取消（终态）：未发布前取消。 */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** 非 DRAFT 非终态：同一 mapping 同时最多 1 个（部分唯一索引 uk_active_change）。 */
    public boolean isActive() {
        return this != DRAFT && !isTerminal();
    }
}

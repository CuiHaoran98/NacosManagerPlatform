package com.nacos.ncg.scheduler.domain;

/**
 * 定时任务状态机（详细设计 v1.1 §4.2 / spec E.2.3）。
 * SCHEDULED→TRIGGERED→PRECHECK→DISPATCHED→DONE；PRECHECK 失败→RETURNED（回 APPROVED，
 * 不写 Nacos、不补发）；DISPATCHED 后走标准发布流程。
 * 同一变更单最多 1 个活动任务（部分唯一索引 uk_schedule_change）。
 */
public enum ScheduleJobState {
    SCHEDULED,
    TRIGGERED,
    PRECHECK,
    DISPATCHED,
    /** 复核失败退回：变更单回 APPROVED 并告警（REL/SCH-xxx）。 */
    RETURNED,
    /** 已取消（到点前可取消/改期，改期不重审批）。 */
    CANCELLED,
    DONE;

    public boolean isActive() {
        return this == SCHEDULED || this == TRIGGERED || this == PRECHECK || this == DISPATCHED;
    }
}

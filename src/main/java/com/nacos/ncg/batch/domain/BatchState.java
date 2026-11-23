package com.nacos.ncg.batch.domain;

/**
 * 批量任务状态机（详细设计 v1.1 §4.2 / spec E.2.4）。
 * CREATED→RUNNING→SUCCEEDED|PARTIAL_SUCCESS|FAILED；
 * PARTIAL_SUCCESS 只允许重试失败子项，成功子项不重发（R-SCH-05 / uk_batch_item）。
 */
public enum BatchState {
    CREATED,
    RUNNING,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED
}

package com.nacos.ncg.audit.domain;

/**
 * 直连检测事件状态机（详细设计 v1.1 §4.2 / spec E.2.5）。
 * DETECTED→MATCHED（命中平台发布记录）|UNAUTHORIZED（未授权变更→审计+发件箱+@负责人）
 * |BLIND_SPOT（历史缺口，不推断为无变更）→NOTIFIED→CLOSED。
 * 同一 Nacos 历史记录只报警一次（uk_direct(env_id,data_id,nacos_history_id)）。
 */
public enum DirectChangeState {
    DETECTED,
    MATCHED,
    UNAUTHORIZED,
    BLIND_SPOT,
    NOTIFIED,
    CLOSED
}

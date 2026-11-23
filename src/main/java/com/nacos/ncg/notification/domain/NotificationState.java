package com.nacos.ncg.notification.domain;

/**
 * 通知发件箱状态机（详细设计 v1.1 §4.2 §11 / spec E.2.6）。
 * QUEUED→SENDING→SENT|RETRYING→DEAD；失败重试 3 次（1/5/15 分钟退避 [TBD]），
 * 耗尽转 DEAD+审计告警（NTF-502-001 最多重试 3 次）；通知失败绝不回滚业务（C-ARC-07）。
 */
public enum NotificationState {
    QUEUED,
    SENDING,
    SENT,
    RETRYING,
    DEAD
}

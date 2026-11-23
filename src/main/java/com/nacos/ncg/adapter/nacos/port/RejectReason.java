package com.nacos.ncg.adapter.nacos.port;

/**
 * 发布拒绝原因（详细设计 v1.1 §8.2）：仅限四种，不新增。
 */
public enum RejectReason {
    /** 基线不一致（写前 CAS 失败） */
    BASELINE,
    /** 认证失败 */
    AUTH,
    /** 实例不可达（写入未发出） */
    UNAVAILABLE,
    /** 内容格式错误 */
    INVALID
}

package com.nacos.ncg.release.domain;

/**
 * 发布记录被拒的具体原因（详细设计 v1.1 §8 / spec E.1.2）。
 * 仅 outcome=REJECTED 时填写；INDETERMINATE 不属于拒绝，单列。
 */
public enum RejectReason {
    /** 写入前读到的当前版本与提交时冻结的 baseline_md5 不一致（基线漂移→退 PENDING_APPROVAL 三方 Diff）。 */
    BASELINE,
    /** Nacos 认证失败（token 无效/过期）。 */
    AUTH,
    /** 实例不可达（连接失败/超时）。 */
    UNAVAILABLE,
    /** 内容格式非法（Nacos 拒收）。 */
    INVALID
}

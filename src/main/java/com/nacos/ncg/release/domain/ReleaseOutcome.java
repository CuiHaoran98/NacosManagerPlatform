package com.nacos.ncg.release.domain;

/**
 * 发布记录状态机（详细设计 v1.1 §4.2 / spec E.2.2）。
 * CREATED→WRITING→VERIFIED|REJECTED|INDETERMINATE；终态不可改，对账只追加新记录。
 * VERIFIED 的必要条件：readback_md5 = expected_md5（读回一致，C-ARC-05）。
 */
public enum ReleaseOutcome {
    /** 已创建，尚未调用适配器。 */
    CREATED,
    /** 事务 A 已提交，适配器写入已发出（发布意图审计已写）。 */
    WRITING,
    /** 写入成功且读回 MD5 与写入前一致（终态）。 */
    VERIFIED,
    /** 适配器明确拒绝：reject_reason=BASELINE|AUTH|UNAVAILABLE|INVALID（终态）。 */
    REJECTED,
    /** 写入已发出但结果不确定：超时/读回失败（终态），禁止自动重试，进入人工对账。 */
    INDETERMINATE;

    public boolean isTerminal() {
        return this == VERIFIED || this == REJECTED || this == INDETERMINATE;
    }
}

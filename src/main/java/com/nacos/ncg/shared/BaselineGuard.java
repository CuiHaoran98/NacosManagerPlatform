package com.nacos.ncg.shared;

/**
 * 基线守卫（详细设计 v1.1 §4 领域服务 / spec FR-02、R-CR-04、流程 7.1/7.3/7.5）。
 * 规则：
 * 1) 提交时冻结 baseline_md5（提交后不可改、不回写，即使 Nacos 侧又变化）；
 * 2) 发布前预检重读当前值：md5 与 baseline 一致→放行；不一致→基线漂移，
 *    变更单退 PENDING_APPROVAL 并展示三方 Diff（baseline/当前/目标）；
 * 3) 写入后读回：readback_md5 与写入前计算的 expected_md5 一致才 VERIFIED。
 */
public final class BaselineGuard {

    public enum Check {
        /** 当前值与基线一致，允许发布。 */
        OK,
        /** 基线漂移：Nacos 当前值已不是提交时的版本。 */
        DRIFTED,
        /** 基线未冻结（草稿态调用了预检，调用方 bug）。 */
        NOT_FROZEN
    }

    /** 发布前预检：比较提交时冻结的 baseline_md5 与发布前重读的当前 md5。 */
    public Check precheck(String frozenBaselineMd5, String currentMd5) {
        if (frozenBaselineMd5 == null) {
            return Check.NOT_FROZEN;
        }
        return frozenBaselineMd5.equalsIgnoreCase(currentMd5) ? Check.OK : Check.DRIFTED;
    }

    /** 写后读回校验：readback 与写入前计算的 expected 一致才可 VERIFIED（C-ARC-05）。 */
    public boolean readbackVerified(String expectedMd5, String readbackMd5) {
        return expectedMd5 != null && expectedMd5.equalsIgnoreCase(readbackMd5);
    }
}

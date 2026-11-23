package com.nacos.ncg.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * 风险计算器（详细设计 v1.1 §4 领域服务 / spec FR-07、R-IMP-02/03/04）。
 * 规则：
 * 1) 风险因子 7 项：生产环境、敏感配置、删除类变更、Diff 规模超阈值、订阅实例数超阈值、
 *    近 7 天同配置有失败/回滚、非工作时间发布；
 * 2) 命中 HIGH 专属因子（生产+敏感、删除、显式高风险请求）→ HIGH；命中多项 MEDIUM 因子→HIGH；
 * 3) 影响面无法确定（订阅映射缺失/实例不可达）→ 记入 unknowns，等级上调一级（不降级）；
 * 4) 用户可以上调最终等级，绝不下调（R-IMP-03）。
 */
public final class RiskCalculator {

    /** Diff 行数阈值（spec 附录 F 默认 5000 行 [TBD]）。 */
    private final int diffLineThreshold;
    /** 订阅实例数阈值（[Assumption, TBD]，A-01 闭环后固化）。 */
    private final int instanceCountThreshold;

    public RiskCalculator() {
        this(5000, 30);
    }

    public RiskCalculator(int diffLineThreshold, int instanceCountThreshold) {
        this.diffLineThreshold = diffLineThreshold;
        this.instanceCountThreshold = instanceCountThreshold;
    }

    /**
     * 评估风险。factor 参数为各因子的量化输入；unknown 影响面会被记录并触发上调。
     */
    public RiskAssessment assess(
            boolean prod,
            boolean sensitive,
            boolean deletion,
            int diffLineCount,
            Integer subscriberInstanceCount,   // null=影响面未知（映射缺失/实例不可达）
            boolean recentFailureOrRollback,
            boolean offHours,
            RiskLevel userRequestedLevel) {    // null=未上调

        List<String> factors = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();

        boolean high = false;
        int mediumHits = 0;

        if (deletion) {
            factors.add("delete");
            high = true;
        }
        if (prod && sensitive) {
            factors.add("prod+sensitive");
            high = true;
        }
        if (prod) {
            factors.add("prod");
            mediumHits++;
        }
        if (sensitive) {
            factors.add("sensitive");
            mediumHits++;
        }
        if (diffLineCount > diffLineThreshold) {
            factors.add("diff " + diffLineCount + " lines > " + diffLineThreshold);
            mediumHits++;
        }
        if (subscriberInstanceCount == null) {
            unknowns.add("subscriber instances unknown (mapping missing or instance unreachable)");
        } else if (subscriberInstanceCount > instanceCountThreshold) {
            factors.add("subscribers " + subscriberInstanceCount + " > " + instanceCountThreshold);
            mediumHits++;
        }
        if (recentFailureOrRollback) {
            factors.add("failed/rollback within 7d");
            mediumHits++;
        }
        if (offHours) {
            factors.add("off-hours");
            mediumHits++;
        }

        RiskLevel level;
        if (high || mediumHits >= 3) {
            level = RiskLevel.HIGH;
        } else if (mediumHits >= 1) {
            level = RiskLevel.MEDIUM;
        } else {
            level = RiskLevel.LOW;
        }

        // 影响面未知 → 上调一级（R-IMP-02）
        if (!unknowns.isEmpty() && level != RiskLevel.HIGH) {
            level = level == RiskLevel.LOW ? RiskLevel.MEDIUM : RiskLevel.HIGH;
            factors.add("raised one level: impact unknown");
        }

        // 用户只能上调（R-IMP-03）
        if (userRequestedLevel != null) {
            level = level.raise(userRequestedLevel);
        }

        return new RiskAssessment(level, factors, unknowns);
    }
}

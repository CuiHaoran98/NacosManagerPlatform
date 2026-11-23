package com.nacos.ncg.shared;

import java.util.List;

/**
 * 风险评估结果（详细设计 v1.1 §4 值对象 / spec FR-07、R-IMP-02/03）。
 *
 * @param level      评估出的风险等级
 * @param factors    命中的风险因子说明（如 prod、sensitive、delete、diff 5200 行、实例 30）
 * @param unknowns   无法确定的项（如订阅实例映射缺失→标未知；此时等级上调一级）
 */
public record RiskAssessment(RiskLevel level, List<String> factors, List<String> unknowns) {

    public RiskAssessment {
        factors = List.copyOf(factors);
        unknowns = List.copyOf(unknowns);
    }
}

package com.nacos.ncg.shared;

/**
 * 风险等级（详细设计 v1.1 §4 / spec E.1.1 risk_level）。
 * 系统按风险因子计算初始等级，用户只能上调不能下调（R-IMP-03）。
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public RiskLevel raise(RiskLevel other) {
        return this.compareTo(other) >= 0 ? this : other;
    }
}

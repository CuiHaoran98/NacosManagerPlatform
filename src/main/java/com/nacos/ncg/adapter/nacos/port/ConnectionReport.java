package com.nacos.ncg.adapter.nacos.port;

import java.time.Instant;

/**
 * 连接测试结果（详细设计 v1.1 §8.1 test 方法；FR-10）。
 * 连续探活失败达阈值置实例 UNAVAILABLE 并阻断发布（R-CON-05 / REL-503-001）。
 */
public record ConnectionReport(boolean reachable, boolean authenticated, long latencyMs,
                               Instant testedAt, String detail) {
}

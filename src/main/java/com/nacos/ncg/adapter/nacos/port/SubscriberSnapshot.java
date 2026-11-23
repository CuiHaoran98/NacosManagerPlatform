package com.nacos.ncg.adapter.nacos.port;

import java.time.Instant;
import java.util.List;

/**
 * 订阅者快照（影响面分析输入，详细设计 v1.1 §8.1）。
 * fetchedAt 之后监听关系可能变化，只能标“可能过期”（C-ARC-16）。
 */
public record SubscriberSnapshot(List<Subscriber> subscribers, boolean complete, Instant fetchedAt) {

    public SubscriberSnapshot {
        subscribers = subscribers == null ? List.of() : List.copyOf(subscribers);
    }
}

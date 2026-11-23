package com.nacos.ncg.adapter.nacos.port;

import java.time.Instant;
import java.util.List;

/**
 * 历史分页结果（详细设计 v1.1 §8.1）：分页游标 + fetchedAt。
 * fetchedAt 之后外部可能继续变更，消费方只能标“可能过期”，不得当作事实源（C-ARC-16）。
 */
public record HistoryPage(List<HistoryEntry> entries, String nextCursor, boolean complete, Instant fetchedAt) {

    public HistoryPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}

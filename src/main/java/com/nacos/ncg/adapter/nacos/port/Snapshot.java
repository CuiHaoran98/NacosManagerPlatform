package com.nacos.ncg.adapter.nacos.port;

import java.time.Instant;

/**
 * 一次读取结果（详细设计 v1.1 §4.3）：content、md5、collectedAt、source。
 * 缓存/派生快照不得作为事实源（C-ARC-16）；source 标明读取来源，配合 collectedAt 提示“可能过期”。
 */
public record Snapshot(String content, String md5, Instant collectedAt, Source source) {

    /** 读取来源：LIVE=直连 Nacos 实时读取；HISTORY=由历史记录重建。 */
    public enum Source { LIVE, HISTORY }
}

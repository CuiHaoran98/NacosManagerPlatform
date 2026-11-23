package com.nacos.ncg.adapter.nacos.port;

import java.time.Instant;

/**
 * Nacos 历史记录条目（直连检测匹配用：环境+dataId+历史ID 去重，时间窗+MD5 匹配发布记录）。
 * 字段以直连检测所需最小集为准（详细设计 v1.1 §7.11）。
 */
public record HistoryEntry(String historyId, String namespace, String group, String dataId,
                           String md5, Instant modifiedAt, String modifiedBy, String ops) {
}

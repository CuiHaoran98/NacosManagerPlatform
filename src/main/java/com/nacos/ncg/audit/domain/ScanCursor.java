package com.nacos.ncg.audit.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 直连扫描游标（流程 7.11）：每环境一行增量拉历史；
 * blind_spot_since 标记历史缺口（R-AUD-07）。
 */
@Table("ncg_scan_cursor")
public class ScanCursor implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public ScanCursor() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private ScanCursor(String envId) {
        this.envId = envId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.envId;
    }


    @Id
    private String envId;
    private String lastHistoryId;
    private Instant lastScanAt;
    private Instant blindSpotSince;
    private int consecutiveFailures;

    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getLastHistoryId() { return lastHistoryId; }
    public void setLastHistoryId(String lastHistoryId) { this.lastHistoryId = lastHistoryId; }
    public Instant getLastScanAt() { return lastScanAt; }
    public void setLastScanAt(Instant lastScanAt) { this.lastScanAt = lastScanAt; }
    public Instant getBlindSpotSince() { return blindSpotSince; }
    public void setBlindSpotSince(Instant blindSpotSince) { this.blindSpotSince = blindSpotSince; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
}

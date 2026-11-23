package com.nacos.ncg.envsync.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** 跨环境同步对比快照（FR-11 / 流程 7.8）：只读对比记录，不写目标环境。 */
@Table("ncg_env_sync_snapshot")
public class EnvSyncSnapshot implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public EnvSyncSnapshot() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private EnvSyncSnapshot(String snapshotId) {
        this.snapshotId = snapshotId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.snapshotId;
    }


    @Id
    private String snapshotId;
    private String sourceEnv;
    private String targetEnv;
    private int configCount;
    private Jsonb diffSummary;
    private String createdBy;
    private Instant expiresAt;
    private Instant createdAt;

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public String getSourceEnv() { return sourceEnv; }
    public void setSourceEnv(String sourceEnv) { this.sourceEnv = sourceEnv; }
    public String getTargetEnv() { return targetEnv; }
    public void setTargetEnv(String targetEnv) { this.targetEnv = targetEnv; }
    public int getConfigCount() { return configCount; }
    public void setConfigCount(int configCount) { this.configCount = configCount; }
    public Jsonb getDiffSummary() { return diffSummary; }
    public void setDiffSummary(Jsonb diffSummary) { this.diffSummary = diffSummary; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.nacos.ncg.catalog.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 回收站条目（FR-08 / 流程 7.7）：软删 30 天可恢复（R-SYS-06），
 * purge_at = deleted_at + 30 天，Worker 每日清理过期条目（RECYCLE_PURGE 审计）。
 */
@Table("ncg_recycle_bin")
public class RecycleBinItem implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public RecycleBinItem() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private RecycleBinItem(String itemId) {
        this.itemId = itemId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.itemId;
    }


    @Id
    private String itemId;
    private String mappingId;
    private Instant deletedAt;
    private String deletedBy;
    private Instant purgeAt;
    /** PENDING / RESTORED / PURGED */
    private String restoreStatus;
    private Instant createdAt;
    private Instant updatedAt;

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getMappingId() { return mappingId; }
    public void setMappingId(String mappingId) { this.mappingId = mappingId; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
    public Instant getPurgeAt() { return purgeAt; }
    public void setPurgeAt(Instant purgeAt) { this.purgeAt = purgeAt; }
    public String getRestoreStatus() { return restoreStatus; }
    public void setRestoreStatus(String restoreStatus) { this.restoreStatus = restoreStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

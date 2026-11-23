package com.nacos.ncg.catalog.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 配置映射（E.1.4 / R-CAT-01~04）：namespace+group+dataId 全局唯一，
 * 一配置只归属一应用一环境；敏感标记驱动脱敏；软删状态 DELETED 进回收站。
 */
@Table("ncg_config_mapping")
public class ConfigMapping implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public ConfigMapping() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private ConfigMapping(String mappingId) {
        this.mappingId = mappingId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.mappingId;
    }


    @Id
    private String mappingId;
    private String appId;
    private String envId;
    private String namespace;
    private String groupName;
    private String dataId;
    /** 固化格式（MUST-15）：PROPERTIES / YAML / JSON / TEXT，纳管时按后缀写入不可变。 */
    private String format;
    private boolean sensitive;
    private String ownerId;
    private String status;            // MANAGED / UNMANAGED / DELETED
    private String lastSnapshotMd5;
    private Instant lastCollectedAt;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getMappingId() { return mappingId; }
    public void setMappingId(String mappingId) { this.mappingId = mappingId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastSnapshotMd5() { return lastSnapshotMd5; }
    public void setLastSnapshotMd5(String lastSnapshotMd5) { this.lastSnapshotMd5 = lastSnapshotMd5; }
    public Instant getLastCollectedAt() { return lastCollectedAt; }
    public void setLastCollectedAt(Instant lastCollectedAt) { this.lastCollectedAt = lastCollectedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

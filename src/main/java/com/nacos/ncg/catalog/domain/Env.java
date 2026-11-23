package com.nacos.ncg.catalog.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalTime;

/** 环境（E.1.6；R-CON-01：一环境只绑一实例）。 */
@Table("ncg_env")
public class Env implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Env() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Env(String envId) {
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
    private String envCode;        // dev / staging / prod
    private String displayName;
    private int sortOrder;
    private LocalTime releaseWindowStart;  // 空=全天（PRD 决策 15）
    private LocalTime releaseWindowEnd;
    private String status;         // ENABLED / DISABLED
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getEnvCode() { return envCode; }
    public void setEnvCode(String envCode) { this.envCode = envCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public LocalTime getReleaseWindowStart() { return releaseWindowStart; }
    public void setReleaseWindowStart(LocalTime releaseWindowStart) { this.releaseWindowStart = releaseWindowStart; }
    public LocalTime getReleaseWindowEnd() { return releaseWindowEnd; }
    public void setReleaseWindowEnd(LocalTime releaseWindowEnd) { this.releaseWindowEnd = releaseWindowEnd; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

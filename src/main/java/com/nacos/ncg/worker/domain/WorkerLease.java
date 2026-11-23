package com.nacos.ncg.worker.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** Worker 租约（§12）：lease_key 唯一，TTL 30s，心跳续租 10s。 */
@Table("ncg_worker_lease")
public class WorkerLease implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public WorkerLease() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private WorkerLease(String leaseKey) {
        this.leaseKey = leaseKey;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.leaseKey;
    }


    @Id
    private String leaseKey;
    private String ownerId;
    private Instant expiresAt;
    private Instant heartbeatAt;
    private long version;

    public String getLeaseKey() { return leaseKey; }
    public void setLeaseKey(String leaseKey) { this.leaseKey = leaseKey; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

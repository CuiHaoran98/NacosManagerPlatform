package com.nacos.ncg.catalog.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Nacos 实例注册（E.1.7 / R-CON-01/02/04）：一环境只绑一实例；凭证密文存储，界面不回显。
 * endpoint/凭证在 P0 也可来自配置（NacosConnectionRegistry），DB 注册用于探活与影响面标注。[TBD]
 */
@Table("ncg_instance")
public class Instance implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Instance() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Instance(String instanceId) {
        this.instanceId = instanceId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.instanceId;
    }


    @Id
    private String instanceId;
    private String envId;
    private String endpoint;
    private byte[] credentialCiphertext;   // enc:v1 密文
    private String status;                 // ONLINE / DEGRADED / UNAVAILABLE
    private int consecutiveFailures;
    private Instant lastProbeAt;
    private Integer lastProbeLatencyMs;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public byte[] getCredentialCiphertext() { return credentialCiphertext; }
    public void setCredentialCiphertext(byte[] credentialCiphertext) { this.credentialCiphertext = credentialCiphertext; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getLastProbeAt() { return lastProbeAt; }
    public void setLastProbeAt(Instant lastProbeAt) { this.lastProbeAt = lastProbeAt; }
    public Integer getLastProbeLatencyMs() { return lastProbeLatencyMs; }
    public void setLastProbeLatencyMs(Integer lastProbeLatencyMs) { this.lastProbeLatencyMs = lastProbeLatencyMs; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

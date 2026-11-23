package com.nacos.ncg.notification.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 通知绑定（§11.3）：应用×环境×渠道（IN_APP / WECOM）绑定企微群机器人目标；
 * target_ciphertext 存加密后的 webhook（IN_APP 可空）。uk(app_id, env_id, channel)。
 */
@Table("ncg_notify_binding")
public class NotifyBinding implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public NotifyBinding() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private NotifyBinding(String bindingId) {
        this.bindingId = bindingId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.bindingId;
    }


    @Id
    private String bindingId;
    private String appId;
    private String envId;
    private String channel;
    private byte[] targetCiphertext;
    private String status;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public byte[] getTargetCiphertext() { return targetCiphertext; }
    public void setTargetCiphertext(byte[] targetCiphertext) { this.targetCiphertext = targetCiphertext; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

package com.nacos.ncg.notification.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 通知发件箱（E.1.10 / §11.2 / C-ARC-07）：业务同事务入队 QUEUED，
 * Worker FOR UPDATE SKIP LOCKED 领取；失败重试 3 次后 DEAD+审计；不回滚业务。
 */
@Table("ncg_notification")
public class Notification implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Notification() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Notification(String notificationId) {
        this.notificationId = notificationId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.notificationId;
    }


    @Id
    private String notificationId;
    private String channel;        // IN_APP / WECOM
    private String eventType;      // 通知事件类型枚举（§11.5）
    private String objectType;
    private String objectId;
    private Jsonb payloadMasked;   // 只含对象/动作/结果/操作人/链接（R-NTF-03）
    private String dedupKey;
    private String status;         // QUEUED/SENDING/SENT/RETRYING/DEAD
    private int retryCount;
    private Instant nextRetryAt;
    private Instant createdAt;
    private Instant updatedAt;

    public String getNotificationId() { return notificationId; }
    public void setNotificationId(String notificationId) { this.notificationId = notificationId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public Jsonb getPayloadMasked() { return payloadMasked; }
    public void setPayloadMasked(Jsonb payloadMasked) { this.payloadMasked = payloadMasked; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

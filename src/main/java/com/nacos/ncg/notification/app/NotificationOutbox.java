package com.nacos.ncg.notification.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.notification.domain.Notification;
import com.nacos.ncg.notification.repo.NotificationRepository;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知发件箱（§11.2 / C-ARC-07 / R-NTF-01~05）：
 * enqueue 参与调用方业务事务（MANDATORY）——业务提交则通知必入队；发送由 Worker 异步完成，
 * 发送失败重试 3 次（1/5/15 分钟 [TBD]）后 DEAD+审计，绝不回滚业务。
 * dedup_key = 事件类型 + 对象 + 5 分钟聚合窗口（R-NTF-05）。
 */
@Service
public class NotificationOutbox {

    /** 通知事件类型（§11.5 固定 11 个，新增需版本化）。 */
    public enum EventType {
        CHANGE_SUBMITTED, CHANGE_APPROVED, CHANGE_REJECTED,
        RELEASE_FAILED, RELEASE_INDETERMINATE,
        SCHEDULE_RECHECK_FAILED, DIRECT_CHANGE_UNAUTHORIZED,
        BATCH_PARTIAL_FAILED, ACCOUNT_DISABLED,
        AUDIT_CHAIN_BROKEN, NOTIFY_DEAD
    }

    private final NotificationRepository notifications;
    private final ObjectMapper json = new ObjectMapper();

    public NotificationOutbox(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** 业务事务内入队。payload 只含对象/动作/结果/操作人/链接（R-NTF-03），敏感值一律脱敏。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification enqueue(EventType eventType, String objectType, String objectId,
                                Map<String, Object> payloadMasked, String channel) {
        Instant now = Instant.now();
        Notification n = new Notification();
        n.setNotificationId(Ulid.generate(now));
        n.setChannel(channel == null ? "IN_APP" : channel);
        n.setEventType(eventType.name());
        n.setObjectType(objectType);
        n.setObjectId(objectId);
        n.setPayloadMasked(Jsonb.of(writeJson(payloadMasked)));
        n.setDedupKey(dedupKey(eventType, objectId, now));
        n.setStatus("QUEUED");
        n.setRetryCount(0);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        return notifications.save(n);
    }

    /** 5 分钟聚合窗口键（R-NTF-05）。 */
    static String dedupKey(EventType eventType, String objectId, Instant at) {
        long window = at.getEpochSecond() / 300;
        return eventType.name() + ":" + objectId + ":" + window;
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(new LinkedHashMap<>(map));
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }
}

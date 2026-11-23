package com.nacos.ncg.web;

import com.nacos.ncg.notification.domain.Notification;
import com.nacos.ncg.notification.repo.NotificationRepository;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知 REST（FR-09）：通知列表（发件箱视图，状态过滤）。
 * payload 只含脱敏摘要（payloadMasked），不含配置原文（R-NTF-02）。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          Principal principal) {
        ActorResolver.requireActor(principal);
        Iterable<Notification> rows = status == null || status.isBlank()
                ? notifications.findAll()
                : notifications.findByStatusInOrderByCreatedAtAsc(List.of(status));
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .map(NotificationController::view).toList();
    }

    private static Map<String, Object> view(Notification n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notificationId", n.getNotificationId());
        body.put("channel", n.getChannel());
        body.put("eventType", n.getEventType());
        body.put("objectType", n.getObjectType());
        body.put("objectId", n.getObjectId());
        body.put("status", n.getStatus());
        body.put("retryCount", n.getRetryCount());
        body.put("nextRetryAt", n.getNextRetryAt());
        body.put("createdAt", n.getCreatedAt());
        body.put("payloadMasked", n.getPayloadMasked() == null ? null : n.getPayloadMasked().json());
        return body;
    }
}

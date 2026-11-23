package com.nacos.ncg.notification.app;

import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.notification.domain.Notification;
import com.nacos.ncg.notification.repo.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 通知派发（FR-09 / 流程 7.12 / C-ARC-07 / R-NTF-02）：
 * 业务事务入队，Worker 轮询派发；失败按 1/5/15 分钟退避重试 [TBD]，3 次后 DEAD+NOTIFY_DEAD 审计；
 * 通知失败绝不回滚业务（§13）。
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    /** 重试退避 1/5/15 分钟 [A TBD]。 */
    private static final long[] BACKOFF_MINUTES = {1, 5, 15};

    private final NotificationRepository notifications;
    private final WecomSender wecomSender;
    private final AuditWriter auditWriter;
    private final TransactionTemplate tx;

    public NotificationDispatcher(NotificationRepository notifications, WecomSender wecomSender,
                                  AuditWriter auditWriter, PlatformTransactionManager txm) {
        this.notifications = notifications;
        this.wecomSender = wecomSender;
        this.auditWriter = auditWriter;
        this.tx = new TransactionTemplate(txm);
    }

    /** 派发一批到期通知（Worker 每 10s 轮询）。 */
    public int dispatchDue(int limit) {
        List<Notification> due = notifications.findDue(Instant.now(), limit);
        for (Notification n : due) {
            dispatchOne(n);
        }
        return due.size();
    }

    private void dispatchOne(Notification n) {
        try {
            if ("WECOM".equals(n.getChannel())) {
                wecomSender.send(null, buildText(n));
            }
            // IN_APP：入队即可见，直接标记 SENT
            n.setStatus("SENT");
            n.setUpdatedAt(Instant.now());
            notifications.save(n);
        } catch (Exception ex) {
            int next = n.getRetryCount() + 1;
            n.setRetryCount(next);
            if (next > BACKOFF_MINUTES.length) {
                n.setStatus("DEAD");
                n.setUpdatedAt(Instant.now());
                notifications.save(n);
                tx.executeWithoutResult(s -> auditWriter.append(AuditWriter.Command.system(
                        AuditAction.NOTIFY_DEAD, "NOTIFICATION", n.getNotificationId(), null)));
                log.warn("notification {} dead after {} retries", n.getNotificationId(), next - 1);
            } else {
                n.setStatus("RETRYING");
                n.setNextRetryAt(Instant.now().plus(Duration.ofMinutes(BACKOFF_MINUTES[next - 1])));
                n.setUpdatedAt(Instant.now());
                notifications.save(n);
            }
        }
    }

    private static String buildText(Notification n) {
        return "[NCG] " + n.getEventType() + " " + n.getObjectType() + "/" + n.getObjectId()
                + " " + n.getPayloadMasked();
    }
}

package com.nacos.ncg.notification.repo;

import com.nacos.ncg.notification.domain.Notification;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends CrudRepository<Notification, String> {

    /** Worker 领取：到期的 QUEUED/RETRYING 通知（实际领取用 @Query + FOR UPDATE SKIP LOCKED 见服务）。 */
    List<Notification> findByStatusInOrderByCreatedAtAsc(java.util.Collection<String> statuses);

    Optional<Notification> findFirstByDedupKey(String dedupKey);

    @Query("SELECT * FROM ncg_notification WHERE status IN ('QUEUED','RETRYING') AND (next_retry_at IS NULL OR next_retry_at <= :now) ORDER BY created_at LIMIT :limit")
    List<Notification> findDue(@org.springframework.data.repository.query.Param("now") Instant now,
                               @org.springframework.data.repository.query.Param("limit") int limit);
}

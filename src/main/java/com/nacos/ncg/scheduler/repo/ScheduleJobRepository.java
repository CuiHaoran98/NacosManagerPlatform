package com.nacos.ncg.scheduler.repo;

import com.nacos.ncg.scheduler.domain.ScheduleJob;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduleJobRepository extends CrudRepository<ScheduleJob, String> {

    Optional<ScheduleJob> findByChangeNo(String changeNo);

    List<ScheduleJob> findByStatus(String status);

    /** 到点任务（Worker 轮询）：活动状态且 execute_at <= now，按时间排序。 */
    @Query("SELECT * FROM ncg_schedule_job WHERE status IN ('SCHEDULED','TRIGGERED','PRECHECK','DISPATCHED') "
            + "AND execute_at <= :now ORDER BY execute_at ASC LIMIT :limit")
    List<ScheduleJob> findDue(@Param("now") Instant now, @Param("limit") int limit);

    /** 租约抢占：仅当租约空闲或过期时接管（乐观条件更新）。 */
    @Query("UPDATE ncg_schedule_job SET lease_owner = :owner, lease_expires_at = :expires, "
            + "status = 'TRIGGERED', updated_at = :now "
            + "WHERE job_id = :jobId AND (lease_owner IS NULL OR lease_expires_at < :now) "
            + "AND status IN ('SCHEDULED','TRIGGERED') RETURNING job_id")
    String claim(@Param("jobId") String jobId, @Param("owner") String owner,
                 @Param("expires") Instant expires, @Param("now") Instant now);

    @Query("UPDATE ncg_schedule_job SET lease_owner = :owner, lease_expires_at = :expires, updated_at = :now "
            + "WHERE job_id = :jobId AND lease_owner = :owner RETURNING job_id")
    String renewLease(@Param("jobId") String jobId, @Param("owner") String owner,
                      @Param("expires") Instant expires, @Param("now") Instant now);

    @Query("UPDATE ncg_schedule_job SET lease_owner = NULL, lease_expires_at = NULL, updated_at = :now "
            + "WHERE job_id = :jobId AND lease_owner = :owner RETURNING job_id")
    String releaseLease(@Param("jobId") String jobId, @Param("owner") String owner, @Param("now") Instant now);
}

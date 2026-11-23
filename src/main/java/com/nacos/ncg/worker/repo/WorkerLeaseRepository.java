package com.nacos.ncg.worker.repo;

import com.nacos.ncg.worker.domain.WorkerLease;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface WorkerLeaseRepository extends CrudRepository<WorkerLease, String> {

    /** 抢占：首次插入或过期接管。 */
    @Query("INSERT INTO ncg_worker_lease(lease_key, owner_id, expires_at, heartbeat_at, version) "
            + "VALUES (:key, :owner, :expires, :now, 0) "
            + "ON CONFLICT (lease_key) DO UPDATE SET owner_id = :owner, expires_at = :expires, "
            + "heartbeat_at = :now, version = ncg_worker_lease.version + 1 "
            + "WHERE ncg_worker_lease.expires_at < :now RETURNING lease_key")
    String acquire(@Param("key") String key, @Param("owner") String owner,
                   @Param("expires") Instant expires, @Param("now") Instant now);

    @Query("UPDATE ncg_worker_lease SET expires_at = :expires, heartbeat_at = :now, "
            + "version = version + 1 WHERE lease_key = :key AND owner_id = :owner RETURNING lease_key")
    String renew(@Param("key") String key, @Param("owner") String owner,
                 @Param("expires") Instant expires, @Param("now") Instant now);

    @Query("UPDATE ncg_worker_lease SET expires_at = :now WHERE lease_key = :key AND owner_id = :owner RETURNING lease_key")
    String release(@Param("key") String key, @Param("owner") String owner, @Param("now") Instant now);
}

package com.nacos.ncg.worker.app;

import com.nacos.ncg.worker.repo.WorkerLeaseRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 租约管理（§12）：worker:active TTL 30s、心跳续租 10s；
 * 崩溃后租约过期即被热备接管（R-SYS-08 / §13）。
 */
@Service
public class LeaseManager {

    public static final String WORKER_ACTIVE = "worker:active";

    private final WorkerLeaseRepository leases;

    public LeaseManager(WorkerLeaseRepository leases) {
        this.leases = leases;
    }

    /** 抢占或续租：持有者为 workerId。返回 true 表示本轮可执行任务。 */
    public boolean hold(String key, String workerId) {
        Instant now = Instant.now();
        Instant expires = now.plus(30, ChronoUnit.SECONDS);
        String acquired = leases.acquire(key, workerId, expires, now);
        if (acquired != null) {
            return true;
        }
        String renewed = leases.renew(key, workerId, expires, now);
        return renewed != null;
    }

    public void release(String key, String workerId) {
        leases.release(key, workerId, Instant.now());
    }
}

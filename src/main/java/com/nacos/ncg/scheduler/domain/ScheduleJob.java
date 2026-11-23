package com.nacos.ncg.scheduler.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 定时发布任务（FR-12 / 流程 7.9 / R-SCH-01~06）。
 * uk_schedule_change（部分唯一）：同一变更单最多一个活动任务
 * （SCHEDULED|TRIGGERED|PRECHECK|DISPATCHED）。
 */
@Table("ncg_schedule_job")
public class ScheduleJob implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public ScheduleJob() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private ScheduleJob(String jobId) {
        this.jobId = jobId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.jobId;
    }


    @Id
    private String jobId;
    private String changeNo;
    private Instant executeAt;
    private String status;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private int attempts;
    private String lastErrorCode;
    private String createdBy;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getChangeNo() { return changeNo; }
    public void setChangeNo(String changeNo) { this.changeNo = changeNo; }
    public Instant getExecuteAt() { return executeAt; }
    public void setExecuteAt(Instant executeAt) { this.executeAt = executeAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

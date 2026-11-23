package com.nacos.ncg.batch.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** 批量变更父单（FR-12 / 流程 7.10）。 */
@Table("ncg_batch")
public class Batch implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Batch() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Batch(String batchId) {
        this.batchId = batchId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.batchId;
    }


    @Id
    private String batchId;
    private String parentChangeNo;
    private String status;
    /** 批量模式：RELEASE（组批发布，changeNos 模型）/ DRAFT（子草稿批量，契约缺口5 items[] 模型）。 */
    private String mode;
    private int totalCount;
    private int succeededCount;
    private int failedCount;
    private String createdBy;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getParentChangeNo() { return parentChangeNo; }
    public void setParentChangeNo(String parentChangeNo) { this.parentChangeNo = parentChangeNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getSucceededCount() { return succeededCount; }
    public void setSucceededCount(int succeededCount) { this.succeededCount = succeededCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

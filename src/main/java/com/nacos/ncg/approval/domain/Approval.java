package com.nacos.ncg.approval.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** 审批记录（E.1.8 / R-APR-01~05）：每次审批动作留痕，同事务写入。 */
@Table("ncg_approval")
public class Approval implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Approval() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Approval(String approvalId) {
        this.approvalId = approvalId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.approvalId;
    }


    @Id
    private String approvalId;
    private String changeNo;
    private String actorId;
    private String action;             // APPROVE / REJECT / TRANSFER
    private String reason;             // REJECT 必填 ≥10 字
    private String targetApproverId;   // TRANSFER 必填
    private String traceId;
    private Instant createdAt;

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getChangeNo() { return changeNo; }
    public void setChangeNo(String changeNo) { this.changeNo = changeNo; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTargetApproverId() { return targetApproverId; }
    public void setTargetApproverId(String targetApproverId) { this.targetApproverId = targetApproverId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

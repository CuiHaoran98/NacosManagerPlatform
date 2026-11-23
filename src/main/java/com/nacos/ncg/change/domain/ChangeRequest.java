package com.nacos.ncg.change.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 变更单（E.1.1 / spec FR-02）：提交时冻结 baseline_md5、影响面快照、风险（R-CR-04 / R-IMP-01），
 * 提交后不可回写；状态机见 {@link ChangeRequestState}；同映射最多 1 个非草稿非终态单（uk_active_change）。
 */
@Table("ncg_change_request")
public class ChangeRequest implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public ChangeRequest() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private ChangeRequest(String changeNo) {
        this.changeNo = changeNo;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.changeNo;
    }


    @Id
    private String changeNo;            // CR-YYYYMMDD-NNNN
    private String appId;
    private String envId;
    private String mappingId;
    private String changeType;          // CREATE/UPDATE/DELETE/RESTORE/ROLLBACK/SYNC
    private String title;
    private String reason;              // ≥10 字（R-CR-01）
    private byte[] contentCiphertext;   // enc:v1 密文
    private String contentSha256;
    private String baselineMd5;         // 提交时冻结
    private String baselineSnapshotRef;
    private Jsonb impactSnapshot;       // 提交时冻结
    private String riskLevel;           // LOW / MEDIUM / HIGH
    private Jsonb riskFactors;
    private String status;              // ChangeRequestState.name()
    @Version
    private Long version;
    private String submitterId;
    private String publisherId;
    private Instant scheduleAt;         // SCHEDULED 必填（R-SCH-01）
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant closedAt;           // 仅终态写入

    public String getChangeNo() { return changeNo; }
    public void setChangeNo(String changeNo) { this.changeNo = changeNo; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getMappingId() { return mappingId; }
    public void setMappingId(String mappingId) { this.mappingId = mappingId; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public byte[] getContentCiphertext() { return contentCiphertext; }
    public void setContentCiphertext(byte[] contentCiphertext) { this.contentCiphertext = contentCiphertext; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getBaselineMd5() { return baselineMd5; }
    public void setBaselineMd5(String baselineMd5) { this.baselineMd5 = baselineMd5; }
    public String getBaselineSnapshotRef() { return baselineSnapshotRef; }
    public void setBaselineSnapshotRef(String baselineSnapshotRef) { this.baselineSnapshotRef = baselineSnapshotRef; }
    public Jsonb getImpactSnapshot() { return impactSnapshot; }
    public void setImpactSnapshot(Jsonb impactSnapshot) { this.impactSnapshot = impactSnapshot; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Jsonb getRiskFactors() { return riskFactors; }
    public void setRiskFactors(Jsonb riskFactors) { this.riskFactors = riskFactors; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getSubmitterId() { return submitterId; }
    public void setSubmitterId(String submitterId) { this.submitterId = submitterId; }
    public String getPublisherId() { return publisherId; }
    public void setPublisherId(String publisherId) { this.publisherId = publisherId; }
    public Instant getScheduleAt() { return scheduleAt; }
    public void setScheduleAt(Instant scheduleAt) { this.scheduleAt = scheduleAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}

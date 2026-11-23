package com.nacos.ncg.release.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 发布记录（E.1.5 / 流程 7.3）：release_key=changeNo+":"+targetVersion 全局唯一（幂等键 R-REL-07 / C-ARC-20）。
 * 终态 VERIFIED|REJECTED|INDETERMINATE 不可再改；对账只追加审计不改历史。
 */
@Table("ncg_release_record")
public class ReleaseRecord implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public ReleaseRecord() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private ReleaseRecord(String releaseId) {
        this.releaseId = releaseId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.releaseId;
    }


    @Id
    private String releaseId;
    private String releaseKey;
    private String changeNo;
    private String mappingId;
    private String envId;
    private String sourceType;      // MANUAL/SCHEDULED/BATCH/ROLLBACK/RESTORE
    private String targetVersion;
    private String outcome;         // CREATED/WRITING/VERIFIED/REJECTED/INDETERMINATE
    private String rejectReason;    // BASELINE/AUTH/UNAVAILABLE/INVALID
    private String expectedMd5;     // 期望读回 MD5（= 新内容 MD5）；VERIFIED 时必须 = readbackMd5
    private String readbackMd5;
    private Jsonb nacosReceipt;     // 脱敏回执（读回内容不落库）
    private String errorCode;
    private String operatorId;      // 发布人（回滚恢复同样留痕）
    private String systemActorId;   // 定时/批量执行的系统账号（审计记录双方 R-SCH-05）
    private String traceId;
    private Instant startedAt;
    private Instant finishedAt;

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public String getReleaseKey() { return releaseKey; }
    public void setReleaseKey(String releaseKey) { this.releaseKey = releaseKey; }
    public String getChangeNo() { return changeNo; }
    public void setChangeNo(String changeNo) { this.changeNo = changeNo; }
    public String getMappingId() { return mappingId; }
    public void setMappingId(String mappingId) { this.mappingId = mappingId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getExpectedMd5() { return expectedMd5; }
    public void setExpectedMd5(String expectedMd5) { this.expectedMd5 = expectedMd5; }
    public String getReadbackMd5() { return readbackMd5; }
    public void setReadbackMd5(String readbackMd5) { this.readbackMd5 = readbackMd5; }
    public Jsonb getNacosReceipt() { return nacosReceipt; }
    public void setNacosReceipt(Jsonb nacosReceipt) { this.nacosReceipt = nacosReceipt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getSystemActorId() { return systemActorId; }
    public void setSystemActorId(String systemActorId) { this.systemActorId = systemActorId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}

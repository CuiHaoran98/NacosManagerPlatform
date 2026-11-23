package com.nacos.ncg.audit.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 审计事件（E.1.3 / §5.5：append-only 按日哈希链）。
 * 表上有 UPDATE/DELETE 触发器拒绝修改（C-ARC-11），实体只有插入路径。
 */
@Table("ncg_audit_event")
public class AuditEvent implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public AuditEvent() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private AuditEvent(String eventId) {
        this.eventId = eventId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.eventId;
    }


    @Id
    private String eventId;
    private LocalDate chainDate;     // UTC 日分链
    private long seq;                // 当日从 1 递增
    private Instant occurredAt;
    private String actorType;        // USER / SYSTEM
    private String actorId;
    private String sourceIp;
    private String action;           // AuditAction.name()
    private String objectType;
    private String objectId;
    private Jsonb beforeMasked;
    private Jsonb afterMasked;
    private String result;           // SUCCESS / FAILURE
    private String errorCode;
    private String changeNo;
    private String traceId;
    private String prevHash;
    private String eventHash;
    private String exportRef;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDate getChainDate() { return chainDate; }
    public void setChainDate(LocalDate chainDate) { this.chainDate = chainDate; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public Jsonb getBeforeMasked() { return beforeMasked; }
    public void setBeforeMasked(Jsonb beforeMasked) { this.beforeMasked = beforeMasked; }
    public Jsonb getAfterMasked() { return afterMasked; }
    public void setAfterMasked(Jsonb afterMasked) { this.afterMasked = afterMasked; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getChangeNo() { return changeNo; }
    public void setChangeNo(String changeNo) { this.changeNo = changeNo; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getEventHash() { return eventHash; }
    public void setEventHash(String eventHash) { this.eventHash = eventHash; }
    public String getExportRef() { return exportRef; }
    public void setExportRef(String exportRef) { this.exportRef = exportRef; }
}

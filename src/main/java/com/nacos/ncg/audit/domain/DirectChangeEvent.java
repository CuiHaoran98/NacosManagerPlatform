package com.nacos.ncg.audit.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 直连变更事件（FR-05 / 流程 7.11 / R-AUD-05/06/07）。
 * uk_direct(env_id,data_id,nacos_history_id)：同一历史记录只报警一次。
 */
@Table("ncg_direct_change_event")
public class DirectChangeEvent implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public DirectChangeEvent() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private DirectChangeEvent(String eventId) {
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
    private String envId;
    private String dataId;
    private String nacosHistoryId;
    private Instant detectedAt;
    private String matchReleaseId;
    private String verdict;
    private Instant notifiedAt;
    private Instant closedAt;
    private Instant createdAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }
    public String getNacosHistoryId() { return nacosHistoryId; }
    public void setNacosHistoryId(String nacosHistoryId) { this.nacosHistoryId = nacosHistoryId; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getMatchReleaseId() { return matchReleaseId; }
    public void setMatchReleaseId(String matchReleaseId) { this.matchReleaseId = matchReleaseId; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant notifiedAt) { this.notifiedAt = notifiedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}

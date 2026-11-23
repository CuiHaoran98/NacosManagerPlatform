package com.nacos.ncg.auth.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 授权记录（E.1.11 / FR-06 / R-AUTH-02~05）：主体(USER/GROUP) × 应用 × 环境 × 权限点。
 * 临时授权 expires_at 到期即失效；唯一键 (subject_type,subject_id,app_id,env_id,permission)。
 */
@Table("ncg_grant")
public class Grant implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Grant() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Grant(String grantId) {
        this.grantId = grantId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.grantId;
    }


    @Id
    private String grantId;
    private String subjectType;   // USER / GROUP / ROLE_TEMPLATE
    private String subjectId;
    private String appId;
    private String envId;
    private String permission;    // 10 权限点（PermissionPoint）
    private String grantedBy;
    private Instant expiresAt;
    private Instant createdAt;

    public String getGrantId() { return grantId; }
    public void setGrantId(String grantId) { this.grantId = grantId; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

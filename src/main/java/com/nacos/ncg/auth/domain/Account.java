package com.nacos.ncg.auth.domain;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 平台自建账号（E.1.1 / FR-06 / R-AUTH-01，P0 无 SSO）。
 * 密码 Argon2id；失败 5 次锁 15 分钟 [A-08, TBD]；状态 ENABLED/DISABLED/FROZEN。
 */
@Table("ncg_account")
public class Account implements Persistable<String> {

    @Transient
    private boolean persisted = false;

    /** 显式无参构造 = 新建路径（isNew()=true → INSERT；ULID 主键由业务侧赋值）。 */
    public Account() {
    }

    /** 持久化构造：从数据库水合走这里（isNew()=false → save() 走 UPDATE）。 */
    @PersistenceCreator
    private Account(String accountId) {
        this.accountId = accountId;
        this.persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @Override
    @Transient
    public String getId() {
        return this.accountId;
    }


    @Id
    private String accountId;
    private String loginName;
    private String displayName;
    private String passwordHash;
    private String status;            // ENABLED / DISABLED / FROZEN
    private int failedAttempts;
    private Instant lockedUntil;
    private boolean isAdmin;
    private Instant lastLoginAt;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public boolean isAdmin() { return isAdmin; }
    public void setIsAdmin(boolean isAdmin) { this.isAdmin = isAdmin; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

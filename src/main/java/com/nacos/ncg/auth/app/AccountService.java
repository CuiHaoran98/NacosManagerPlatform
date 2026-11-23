package com.nacos.ncg.auth.app;

import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.repo.AccountRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 账号服务（FR-06 / R-AUTH-01/06/07/08）：
 * 密码 Argon2id，最短 12 位 [A-08, TBD]；失败 5 次锁 15 分钟 [A-08, TBD]；
 * 禁用/启用/创建均审计；状态变更即时生效（R-AUTH-08）。
 */
@Service
public class AccountService {

    /** 登录失败锁定阈值 [A-08, TBD]。 */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_MINUTES = 15;
    public static final int MIN_PASSWORD_LENGTH = 12;

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final AuditWriter auditWriter;

    public AccountService(AccountRepository accounts, PasswordEncoder encoder, AuditWriter auditWriter) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public Account create(String loginName, String displayName, String rawPassword,
                          boolean isAdmin, String operatorId, String traceId) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters (A-08)");
        }
        accounts.findByLoginName(loginName).ifPresent(a -> {
            throw new NcgException(NcgErrorCode.AUTHZ_403_001, "login name already exists: " + loginName);
        });
        Account account = new Account();
        account.setAccountId(Ulid.generate());
        account.setLoginName(loginName);
        account.setDisplayName(displayName == null ? loginName : displayName);
        account.setPasswordHash(encoder.encode(rawPassword));
        account.setStatus("ENABLED");
        account.setFailedAttempts(0);
        account.setIsAdmin(isAdmin);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        Account saved = accounts.save(account);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.ACCOUNT_CREATE,
                "ACCOUNT", saved.getAccountId(), null, null, true, null, null, traceId));
        return saved;
    }

    @Transactional
    public Account setStatus(String accountId, String status, String operatorId, String traceId) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status) && !"FROZEN".equals(status)) {
            throw new NcgException(NcgErrorCode.CR_400_001, "status must be ENABLED/DISABLED/FROZEN");
        }
        Account account = require(accountId);
        String before = account.getStatus();
        account.setStatus(status);
        account.setUpdatedAt(Instant.now());
        Account saved = accounts.save(account);
        AuditAction action = "ENABLED".equals(status)
                ? AuditAction.ACCOUNT_ENABLE : AuditAction.ACCOUNT_DISABLE;
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, action,
                "ACCOUNT", accountId, null, null, true, null, null, traceId));
        return saved;
    }

    /** 登录失败计数（R-AUTH-06）：达阈值锁 LOCK_MINUTES 分钟。 */
    @Transactional
    public void loginFailed(String loginName) {
        accounts.findByLoginName(loginName).ifPresent(a -> {
            int failed = a.getFailedAttempts() + 1;
            a.setFailedAttempts(failed);
            if (failed >= MAX_FAILED_ATTEMPTS) {
                a.setLockedUntil(Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES));
                a.setStatus("FROZEN");
            }
            a.setUpdatedAt(Instant.now());
            accounts.save(a);
        });
    }

    /** 登录成功：清零失败计数、解冻、记 last_login_at。 */
    @Transactional
    public void loginSucceeded(String loginName) {
        accounts.findByLoginName(loginName).ifPresent(a -> {
            a.setFailedAttempts(0);
            a.setLockedUntil(null);
            if ("FROZEN".equals(a.getStatus())) {
                a.setStatus("ENABLED");
            }
            a.setLastLoginAt(Instant.now());
            a.setUpdatedAt(Instant.now());
            accounts.save(a);
        });
    }

    public Account require(String accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "account not found: " + accountId));
    }

    public Account requireByLogin(String loginName) {
        return accounts.findByLoginName(loginName)
                .orElseThrow(() -> new NcgException(NcgErrorCode.AUTH_401_001, "unknown login: " + loginName));
    }
}

package com.nacos.ncg.auth.app;

import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.domain.Grant;
import com.nacos.ncg.auth.repo.AccountRepository;
import com.nacos.ncg.auth.repo.GrantRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.PermissionPoint;
import com.nacos.ncg.shared.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 授权与鉴权（FR-06 / R-AUTH-02~05/10）：
 * 授权主体 USER/GROUP × 应用 × 环境 × 权限点；临时授权到期即失效；
 * fail-closed：无授权即拒绝（AUTHZ-403-001）；权限变更即时生效+审计。
 */
@Service
public class GrantService {

    private final GrantRepository grants;
    private final AccountRepository accounts;
    private final AuditWriter auditWriter;
    private final JdbcTemplate jdbc;

    public GrantService(GrantRepository grants, AccountRepository accounts,
                        AuditWriter auditWriter, JdbcTemplate jdbc) {
        this.grants = grants;
        this.accounts = accounts;
        this.auditWriter = auditWriter;
        this.jdbc = jdbc;
    }

    /** 权限判定（fail-closed）：账号需 ENABLED 且持权限点；is_admin 全域放行。 */
    public boolean hasPermission(String accountId, String appId, String envId, PermissionPoint permission) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.AUTH_401_001, "unknown account"));
        if (!"ENABLED".equals(account.getStatus())) {
            return false;
        }
        if (account.isAdmin()) {
            return true;
        }
        return effectivePermissions(accountId, appId, envId).contains(permission.name());
    }

    public Set<String> effectivePermissions(String accountId, String appId, String envId) {
        List<Grant> list = grants.findEffective(accountId, appId, envId, Instant.now());
        Set<String> result = new LinkedHashSet<>();
        for (Grant g : list) {
            result.add(g.getPermission());
        }
        return result;
    }

    @Transactional
    public Grant grant(String subjectType, String subjectId, String appId, String envId,
                       PermissionPoint permission, Instant expiresAt,
                       String operatorId, String traceId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "userId is required");
        }
        if (appId == null || appId.isBlank() || envId == null || envId.isBlank() || permission == null) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "appId, envId and permission are required");
        }
        Grant g = new Grant();
        g.setGrantId(Ulid.generate());
        g.setSubjectType(subjectType);
        g.setSubjectId(subjectId);
        g.setAppId(appId);
        g.setEnvId(envId);
        g.setPermission(permission.name());
        g.setGrantedBy(operatorId);
        g.setExpiresAt(expiresAt);
        g.setCreatedAt(Instant.now());
        Grant saved;
        try {
            saved = grants.save(g);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            throw new NcgException(NcgErrorCode.AUTHZ_403_001,
                    "grant already exists (uk_grant): " + subjectType + "/" + subjectId
                            + "/" + appId + "/" + envId + "/" + permission);
        }
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.GRANT_CHANGE,
                "GRANT", saved.getGrantId(), null, null, true, null, null, traceId));
        return saved;
    }

    @Transactional
    public void revoke(String grantId, String operatorId, String traceId) {
        Grant g = grants.findById(grantId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "grant not found: " + grantId));
        grants.delete(g);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.GRANT_CHANGE,
                "GRANT", grantId, null, null, true, null, null, traceId));
    }

    // ---- 组成员（无独立实体，直接 SQL：uk_group_member(group_id,account_id)）----

    @Transactional
    public void addMember(String groupId, String accountId, String operatorId, String traceId) {
        jdbc.update("INSERT INTO ncg_group_member(group_id, account_id, added_by) VALUES (?,?,?)",
                groupId, accountId, operatorId);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.GRANT_CHANGE,
                "GROUP_MEMBER", groupId, null, null, true, null, null, traceId));
    }

    @Transactional
    public void removeMember(String groupId, String accountId, String operatorId, String traceId) {
        jdbc.update("DELETE FROM ncg_group_member WHERE group_id = ? AND account_id = ?", groupId, accountId);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.GRANT_CHANGE,
                "GROUP_MEMBER", groupId, null, null, true, null, null, traceId));
    }
}

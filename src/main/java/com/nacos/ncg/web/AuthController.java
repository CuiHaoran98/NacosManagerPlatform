package com.nacos.ncg.web;

import com.nacos.ncg.auth.app.AccountService;
import com.nacos.ncg.auth.app.GrantService;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.domain.Grant;
import com.nacos.ncg.auth.repo.AccountRepository;
import com.nacos.ncg.auth.repo.GrantRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.PermissionPoint;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号/授权 REST（FR-06）：当前用户、账号创建、授权发放/回收、组成员管理。
 * 敏感操作要求 is_admin（P0 无细粒度管理权限点之外的管理员判定）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final GrantService grantService;
    private final GrantRepository grantRepository;

    public AuthController(AccountRepository accounts, AccountService accountService,
                          GrantService grantService, GrantRepository grantRepository) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.grantService = grantService;
        this.grantRepository = grantRepository;
    }

    public record CreateAccountRequest(String loginName, String displayName,
                                       String password, Boolean admin,
                                       String username, java.util.List<String> roles) {}

    public record GrantRequest(String subjectType, String subjectId,
                               String appId, String envId,
                               PermissionPoint permission, Instant expiresAt,
                               String userId) {}

    public record MemberRequest(@NotBlank String accountId) {}

    /** 契约 #3：GET /auth/me → CurrentUser{userId,username,displayName,roles,permissions}（旧字段保留）。 */
    @GetMapping("/me")
    public Map<String, Object> me(Principal principal) {
        Account a = accountService.requireByLogin(principal.getName());
        Map<String, Object> body = ContractViews.userRow(a);
        body.put("permissions", permissionsOf(a));
        return body;
    }

    /** 权限点：管理员=全部 10 点；其余=其 USER 授权并集（授权点驱动前端渲染）。 */
    private java.util.List<String> permissionsOf(Account a) {
        return ContractViews.permissionsOf(a, grantRepository);
    }

    @PostMapping("/accounts")
    public Map<String, Object> createAccount(@RequestBody CreateAccountRequest req, Authentication auth) {
        Account operator = accountService.requireByLogin(auth.getName());
        requireAdmin(operator);
        String loginName = req.loginName() != null ? req.loginName() : req.username();
        boolean admin = Boolean.TRUE.equals(req.admin())
                || (req.roles() != null && req.roles().stream()
                        .anyMatch(r -> r != null && r.equalsIgnoreCase("ADMIN")));
        Account a = accountService.create(loginName, req.displayName(), req.password(),
                admin, operator.getAccountId(), null);
        return ContractViews.userRow(a);
    }

    @PostMapping("/accounts/{accountId}/status")
    public Map<String, Object> setStatus(@PathVariable String accountId, @RequestParam String status,
                                         Authentication auth) {
        Account operator = accountService.requireByLogin(auth.getName());
        requireAdmin(operator);
        Account a = accountService.setStatus(accountId, status, operator.getAccountId(), null);
        return Map.of("accountId", a.getAccountId(), "status", a.getStatus());
    }

    @PostMapping("/grants")
    public Map<String, Object> grant(@RequestBody GrantRequest req, Authentication auth) {
        Account operator = accountService.requireByLogin(auth.getName());
        requireAdmin(operator);
        String subjectId = req.subjectId() != null ? req.subjectId() : req.userId();
        String subjectType = req.subjectType() != null ? req.subjectType() : "USER";
        Grant g = grantService.grant(subjectType, subjectId, req.appId(), req.envId(),
                req.permission(), req.expiresAt(), operator.getAccountId(), null);
        String username = accounts.findById(subjectId).map(Account::getLoginName).orElse(null);
        return ContractViews.grantRow(g, username);
    }

    @DeleteMapping("/grants/{grantId}")
    public ResponseEntity<Void> revoke(@PathVariable String grantId, Authentication auth) {
        Account operator = accountService.requireByLogin(auth.getName());
        requireAdmin(operator);
        grantService.revoke(grantId, operator.getAccountId(), null);
        return ResponseEntity.noContent().build();
    }

    /** 兼容双形态：带 subjectType+subjectId+appId+envId → 旧 {permissions}；否则契约 Grant[] 列表。 */
    @GetMapping("/grants")
    public Object listGrants(@RequestParam(required = false) String subjectType,
                             @RequestParam(required = false) String subjectId,
                             @RequestParam(required = false) String appId,
                             @RequestParam(required = false) String envId) {
        if (subjectType != null && subjectId != null && appId != null && envId != null) {
            return Map.of("permissions", grantService.effectivePermissions(
                    subjectType.equals("USER") ? subjectId : "", appId, envId));
        }
        var rows = (subjectType != null && subjectId != null)
                ? grantRepository.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                : grantRepository.findAll();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Grant g : rows) {
            String username = accounts.findById(g.getSubjectId()).map(Account::getLoginName).orElse(null);
            out.add(ContractViews.grantRow(g, username));
        }
        return out;
    }

    @PostMapping("/groups/{groupId}/members")
    public ResponseEntity<Void> addMember(@PathVariable String groupId, @RequestBody MemberRequest req,
                                          Authentication auth) {
        Account operator = accountService.requireByLogin(auth.getName());
        requireAdmin(operator);
        grantService.addMember(groupId, req.accountId(), operator.getAccountId(), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{groupId}/members/{accountId}")
    public ResponseEntity<Void> removeMember(@PathVariable String groupId, @PathVariable String accountId,
                                             Authentication auth) {
        Account operator = accountService.requireByLogin(auth.getName());
        requireAdmin(operator);
        grantService.removeMember(groupId, accountId, operator.getAccountId(), null);
        return ResponseEntity.noContent().build();
    }

    private static void requireAdmin(Account operator) {
        if (!operator.isAdmin()) {
            throw new NcgException(NcgErrorCode.AUTHZ_403_001,
                    "admin privilege required (R-AUTH-02)");
        }
    }
}

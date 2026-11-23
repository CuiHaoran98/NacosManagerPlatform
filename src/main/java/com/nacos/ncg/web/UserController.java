package com.nacos.ncg.web;

import com.nacos.ncg.auth.app.AccountService;
import com.nacos.ncg.auth.app.GrantService;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.domain.Grant;
import com.nacos.ncg.auth.repo.AccountRepository;
import com.nacos.ncg.auth.repo.GrantRepository;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规格路径 /users 与 /grants（契约 #47/#48）。
 * 与 /auth/accounts、/auth/grants 同实现（AccountService / GrantService），
 * 权限为 ADMIN（授权动作写 GRANT_CHANGE / ACCOUNT_CREATE 审计）。
 * 形状按契约：UserAccount{userId,username,displayName,roles,enabled,createdAt}、
 * Grant{grantId,userId,username,appId,envId,permission,grantedBy,grantedAt}；
 * 兼容旧字段名（loginName/username、password/rawPassword、roles/isAdmin、userId/subjectId）。
 */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final AccountService accountService;
    private final GrantService grantService;
    private final AccountRepository accounts;
    private final GrantRepository grants;

    public UserController(AccountService accountService, GrantService grantService,
                          AccountRepository accounts, GrantRepository grants) {
        this.accountService = accountService;
        this.grantService = grantService;
        this.accounts = accounts;
        this.grants = grants;
    }

    /** 契约 CreateUserRequest{username,displayName,password,roles[]} + 旧字段别名。 */
    public record UserRequest(String loginName, String displayName, String rawPassword, Boolean isAdmin,
                              String username, String password, List<String> roles) {}

    /** 契约 CreateGrantRequest{userId,appId,envId,permission} + 旧字段别名。 */
    public record GrantRequest(String subjectType, String subjectId, String appId, String envId,
                               String permission, Instant expiresAt, String userId) {}

    /** 契约 #47：GET /users（ADMIN）。 */
    @GetMapping("/users")
    public List<Map<String, Object>> listUsers(Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        accountService.requireByLogin(actorId);
        return java.util.stream.StreamSupport.stream(accounts.findAll().spliterator(), false)
                .map(ContractViews::userRow)
                .toList();
    }

    /** 契约 #48：POST /users（ADMIN）。 */
    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody UserRequest req, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        String loginName = req.loginName() != null ? req.loginName() : req.username();
        String password = req.rawPassword() != null ? req.rawPassword() : req.password();
        boolean admin = Boolean.TRUE.equals(req.isAdmin())
                || (req.roles() != null && req.roles().stream()
                        .anyMatch(r -> r != null && r.equalsIgnoreCase("ADMIN")));
        Account created = accountService.create(loginName, req.displayName(), password,
                admin, actorId, null);
        return ContractViews.userRow(created);
    }

    /** 契约 #47：GET /grants（ADMIN）。可按 subjectType/subjectId 或 userId 过滤。 */
    @GetMapping("/grants")
    public List<Map<String, Object>> listGrants(@RequestParam(required = false) String subjectType,
                                                @RequestParam(required = false) String subjectId,
                                                @RequestParam(required = false) String userId,
                                                Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        accountService.requireByLogin(actorId);
        String sid = subjectId != null ? subjectId : userId;
        var rows = (subjectType != null && sid != null)
                ? grants.findBySubjectTypeAndSubjectId(subjectType, sid)
                : (sid != null ? grants.findBySubjectTypeAndSubjectId("USER", sid) : grants.findAll());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Grant g : rows) {
            out.add(ContractViews.grantRow(g,
                    accounts.findById(g.getSubjectId()).map(Account::getLoginName).orElse(null)));
        }
        return out;
    }

    /** 契约 #48：POST /grants（ADMIN）。 */
    @PostMapping("/grants")
    public Map<String, Object> grant(@RequestBody GrantRequest req, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        // D14：permission 缺失/非法原走 Enum.valueOf(null)→NPE 落 500 → 先行 400 CR-400-001；
        // userId/appId/envId 仍由 GrantService 校验（D5 文案「userId is required」等零回归）。
        if (req.permission() == null || req.permission().isBlank()) {
            throw new com.nacos.ncg.shared.NcgException(com.nacos.ncg.shared.NcgErrorCode.CR_400_001,
                    "permission is required");
        }
        com.nacos.ncg.shared.PermissionPoint perm;
        try {
            perm = com.nacos.ncg.shared.PermissionPoint.valueOf(req.permission());
        } catch (IllegalArgumentException ex) {
            throw new com.nacos.ncg.shared.NcgException(com.nacos.ncg.shared.NcgErrorCode.CR_400_001,
                    "permission invalid: " + req.permission());
        }
        String subjectId = req.subjectId() != null ? req.subjectId() : req.userId();
        String subjectType = req.subjectType() != null ? req.subjectType() : "USER";
        var g = grantService.grant(subjectType, subjectId, req.appId(), req.envId(),
                perm, req.expiresAt(), actorId, null);
        return ContractViews.grantRow(g,
                accounts.findById(subjectId).map(Account::getLoginName).orElse(null));
    }
}

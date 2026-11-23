package com.nacos.ncg.web;

import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.Snapshot;
import com.nacos.ncg.auth.app.AccountService;
import com.nacos.ncg.auth.app.GrantService;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.shared.json.Jsonb;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.PermissionPoint;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 映射生命周期 REST（FR-08 / FR-06）：软删（进回收站）与敏感内容查看
 * （VIEW_SENSITIVE + 原因≥10 字 + CONFIG_SENSITIVE_VIEW 审计，明文 no-store）。
 */
@RestController
@RequestMapping("/api/v1")
public class MappingAdminController {

    private final CatalogService catalog;
    private final com.nacos.ncg.release.app.RollbackService rollbackService;
    private final AccountService accountService;
    private final GrantService grantService;
    private final NacosConfigPort port;
    private final AuditWriter auditWriter;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public MappingAdminController(CatalogService catalog,
                                  com.nacos.ncg.release.app.RollbackService rollbackService,
                                  AccountService accountService, GrantService grantService,
                                  NacosConfigPort port, AuditWriter auditWriter,
                                  org.springframework.transaction.support.TransactionTemplate tx) {
        this.catalog = catalog;
        this.rollbackService = rollbackService;
        this.accountService = accountService;
        this.grantService = grantService;
        this.port = port;
        this.auditWriter = auditWriter;
        this.tx = tx;
    }

    /** 敏感项查看请求（契约缺口3）：itemKey 指定唯一查看项，reason ≥10 字。 */
    /** 契约缺口3：itemKey 必填（敏感项按项查看）；1 参构造保留旧调用（itemKey=null → CR-400-001）。 */
    public record SensitiveViewRequest(@NotBlank String reason, String itemKey) {
        public SensitiveViewRequest(String reason) {
            this(reason, null);
        }
    }

    /** 软删映射：进回收站 30 天可恢复（R-SYS-06）；Nacos 侧清空走 DELETE 变更单。 */
    @DeleteMapping("/mappings/{mappingId}")
    public Map<String, Object> deleteMapping(@PathVariable String mappingId,
                                             @RequestParam(required = false) String reason,
                                             Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        var item = rollbackService.softDelete(mappingId, actorId, reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemId", item.getItemId());
        body.put("mappingId", item.getMappingId());
        body.put("purgeAt", item.getPurgeAt());
        body.put("restoreStatus", item.getRestoreStatus());
        return body;
    }

    /** 敏感内容查看（FR-06）：需要 VIEW_SENSITIVE、原因≥10 字，明文不落缓存。 */
    @PostMapping("/mappings/{mappingId}/sensitive-view")
    public ResponseEntity<Map<String, Object>> sensitiveView(@PathVariable String mappingId,
                                                             @RequestBody SensitiveViewRequest request,
                                                             Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        Account account = accountService.requireByLogin(actorId);
        ConfigMapping mapping = catalog.getMapping(mappingId);
        // 权限先行（AUTHZ-403-001 优先于参数校验，避免向无权限方泄露校验差异；AC-18）
        if (!grantService.hasPermission(account.getAccountId(), mapping.getAppId(), mapping.getEnvId(),
                PermissionPoint.VIEW_SENSITIVE)) {
            throw new NcgException(NcgErrorCode.AUTHZ_403_001, "VIEW_SENSITIVE permission required");
        }
        if (request.reason() == null || request.reason().trim().length() < 10) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "sensitive view requires a reason of at least 10 characters (R-AUTH-07)");
        }
        if (request.itemKey() == null || request.itemKey().isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "itemKey is required (契约缺口3：仅返回指定项)");
        }
        Env env = catalog.getEnv(mapping.getEnvId());
        Snapshot snapshot = port.read(new EnvId(env.getEnvCode()),
                new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId()));

        // 契约缺口3：按 itemKey 从解析条目中取唯一一项明文，expiresAt ≈ +30s（前端 30 秒再脱敏）
        String format = mapping.getFormat() != null ? mapping.getFormat()
                : com.nacos.ncg.shared.content.ConfigContentCodec.formatOfDataId(mapping.getDataId());
        var parsed = com.nacos.ncg.shared.content.ConfigContentCodec.parse(format, snapshot.content());
        if (parsed.parseError() != null) {
            throw new NcgException(NcgErrorCode.CR_422_001,
                    "配置内容解析失败（第 " + parsed.parseError().line() + " 行）",
                    Map.of("line", parsed.parseError().line(), "column", parsed.parseError().column()));
        }
        String value = null;
        for (var item : parsed.items()) {
            if (item.key().equals(request.itemKey())) {
                value = item.value();
                break;
            }
        }
        if (value == null) {
            throw new NcgException(NcgErrorCode.CR_400_001, "itemKey not found: " + request.itemKey());
        }

        // 审计：只留查看原因，不落明文（R-AUD-01）；MANDATORY 审计与业务同事务
        final String reason = request.reason();
        tx.executeWithoutResult(s2 -> auditWriter.append(new AuditWriter.Command(
                ActorType.USER, account.getAccountId(), null,
                AuditAction.CONFIG_SENSITIVE_VIEW, "mapping", mappingId,
                null, Jsonb.of("{\"itemKey\": \"" + request.itemKey().replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\", \"reason\": \"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"),
                true, null, null, org.slf4j.MDC.get("traceId"))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemKey", request.itemKey());
        body.put("value", value);
        body.put("expiresAt", Instant.now().plusSeconds(30).toString());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    public record MappingRequest(String appId, String envId, String namespace, String groupName,
                                 String dataId, Boolean sensitive,
                                 String owner, String appName, String remark,
                                 java.util.List<MappingRequest> mappings) {}

    /** 登记映射（契约 #8：POST /mappings，ADMIN；CAT-409-001 重复）。namespace/groupName 缺省用 Nacos 默认值。 */
    @PostMapping("/mappings")
    public Map<String, Object> createMapping(@RequestBody MappingRequest request, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        // D14：{} / 缺字段（appId/envId 缺失炸查询、dataId 缺失炸落库 NOT NULL）→ CR-400-001
        RequestChecks.require("appId", request.appId(), "envId", request.envId(),
                "dataId", request.dataId());
        var saved = catalog.createMapping(request.appId(), request.envId(),
                request.namespace() == null ? "" : request.namespace(),
                request.groupName() == null ? "DEFAULT_GROUP" : request.groupName(),
                request.dataId(), Boolean.TRUE.equals(request.sensitive()), actorId, null);
        return ContractViews.mappingRow(saved);
    }

    /** 更新映射（契约 #9：PUT /mappings/{id}）。appId/envId/sensitive/owner 仅在给出时变更（appName/remark 展示字段忽略）。 */
    @PutMapping("/mappings/{mappingId}")
    public Map<String, Object> updateMapping(@PathVariable String mappingId,
                                             @RequestBody MappingRequest request, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        ConfigMapping current = catalog.getMapping(mappingId);
        String appId = request.appId() != null ? request.appId() : current.getAppId();
        String envId = request.envId() != null ? request.envId() : current.getEnvId();
        boolean sensitive = request.sensitive() != null ? request.sensitive() : current.isSensitive();
        var saved = catalog.updateMapping(mappingId, appId, envId, sensitive,
                request.owner(), actorId, null);
        return ContractViews.mappingRow(saved);
    }

    /** 纳管导入（契约 #11：POST /mappings/import，body {mappings:[...]}→{imported}；兼容单对象）。CAT-409-002 冲突。 */
    @PostMapping("/mappings/import")
    public Map<String, Object> importMapping(@RequestBody MappingRequest request, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        java.util.List<MappingRequest> batch = request.mappings();
        if (batch == null || batch.isEmpty()) {
            batch = java.util.List.of(request);
        }
        int imported = 0;
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (MappingRequest r : batch) {
            if (r.dataId() == null || r.dataId().isBlank()) continue;
            var saved = catalog.importMapping(
                    r.namespace() == null ? "" : r.namespace(),
                    r.groupName() == null ? "DEFAULT_GROUP" : r.groupName(),
                    r.dataId(), r.appId(), r.envId(), actorId, null);
            rows.add(ContractViews.mappingRow(saved));
            imported++;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imported", imported);
        body.put("items", rows);
        return body;
    }

    /**
     * 配置读取（契约缺口2 / §3.2）：{mappingId,dataId,format,md5,collectedAt,
     * items[{key,value,valueType,sensitive}],content(TEXT),parseError{line,column,message}}；
     * 敏感值回 "••••••"，解析失败仍 200 只读浏览（TC-04）。
     */
    @GetMapping("/configs/{mappingId}")
    public Map<String, Object> getConfig(@PathVariable String mappingId) {
        ConfigMapping mapping = catalog.getMapping(mappingId);
        Env env = catalog.getEnv(mapping.getEnvId());
        Snapshot snapshot = port.read(new EnvId(env.getEnvCode()),
                new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId()));
        String format = mapping.getFormat() != null ? mapping.getFormat()
                : com.nacos.ncg.shared.content.ConfigContentCodec.formatOfDataId(mapping.getDataId());
        var parsed = com.nacos.ncg.shared.content.ConfigContentCodec.parse(format, snapshot.content());
        boolean fileSensitive = mapping.isSensitive();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mappingId", mapping.getMappingId());
        body.put("dataId", mapping.getDataId());
        body.put("format", format);
        body.put("md5", snapshot.md5());
        body.put("collectedAt", snapshot.collectedAt());
        if (parsed.parseError() != null) {
            body.put("items", java.util.List.of());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("line", parsed.parseError().line());
            err.put("column", parsed.parseError().column());
            err.put("message", parsed.parseError().message());
            body.put("parseError", err);
            if ("TEXT".equals(format)) {
                body.put("content", fileSensitive ? "••••••" : parsed.content());
            }
        } else if ("TEXT".equals(format)) {
            // TEXT：整文件 content、无 items（TC-03）
            body.put("content", fileSensitive ? "••••••" : parsed.content());
        } else {
            java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (var item : parsed.items()) {
                boolean sensitive = fileSensitive || item.sensitive();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", item.key());
                row.put("value", sensitive ? "••••••" : item.value());
                row.put("valueType", item.valueType());
                row.put("sensitive", sensitive);
                items.add(row);
            }
            body.put("items", items);
        }
        return body;
    }

    /** 规格路径别名：POST /configs/{mappingId}/sensitive-view（§6.2，主路径见上）。 */
    @PostMapping("/configs/{mappingId}/sensitive-view")
    public ResponseEntity<Map<String, Object>> sensitiveViewAlias(@PathVariable String mappingId,
                                                                  @RequestBody SensitiveViewRequest request,
                                                                  Principal principal) {
        return sensitiveView(mappingId, request, principal);
    }

}

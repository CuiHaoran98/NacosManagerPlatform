package com.nacos.ncg.web;

import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.app.InstanceService;
import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.catalog.domain.Instance;
import com.nacos.ncg.shared.crypto.CryptoService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读目录 + 实例注册（契约 #4-6、#36-37 辅助面）。
 * 目录只读不写 Nacos；实例测试经适配器 test()，结果落探测记录。
 * 响应按契约整形（AppItem / mappingRow / instanceRow / InstanceTestResult），
 * 实例行绝不序列化 credentialCiphertext（MUST-NOT-05）。
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalog;
    private final InstanceService instances;
    private final CryptoService crypto;
    private final com.nacos.ncg.adapter.nacos.port.NacosConfigPort port;

    public CatalogController(CatalogService catalog, InstanceService instances, CryptoService crypto,
                             com.nacos.ncg.adapter.nacos.port.NacosConfigPort port) {
        this.catalog = catalog;
        this.instances = instances;
        this.crypto = crypto;
        this.port = port;
    }

    /** 契约：GET /apps → AppItem{appId,appName,owner,envIds[]}（保留原字段）。 */
    @GetMapping("/apps")
    public List<Map<String, Object>> listApps(Principal principal) {
        ActorResolver.requireActor(principal);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (App app : catalog.listApps()) {
            rows.add(ContractViews.appRow(app, envIdsOf(app.getAppId())));
        }
        return rows;
    }

    @GetMapping("/apps/{appId}")
    public Map<String, Object> getApp(Principal principal, @PathVariable String appId) {
        ActorResolver.requireActor(principal);
        return ContractViews.appRow(catalog.getApp(appId), envIdsOf(appId));
    }

    @GetMapping("/envs")
    public List<Env> listEnvs(Principal principal) {
        ActorResolver.requireActor(principal);
        return catalog.listEnvs();
    }

    @GetMapping("/mappings")
    public List<Map<String, Object>> listMappings(Principal principal,
                                                  @RequestParam(required = false) String appId,
                                                  @RequestParam(required = false) String envId) {
        ActorResolver.requireActor(principal);
        List<ConfigMapping> list;
        if (appId != null && envId != null) {
            list = catalog.listMappings(appId, envId);
        } else if (appId != null) {
            list = catalog.listMappingsByApp(appId);
        } else {
            list = catalog.listUnmanaged();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigMapping m : list) rows.add(ContractViews.mappingRow(m));
        return rows;
    }

    @GetMapping("/mappings/unmanaged")
    public List<Map<String, Object>> listUnmanaged(Principal principal) {
        ActorResolver.requireActor(principal);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigMapping m : catalog.listUnmanaged()) rows.add(ContractViews.mappingRow(m));
        return rows;
    }

    @GetMapping("/mappings/{mappingId}")
    public Map<String, Object> getMapping(Principal principal, @PathVariable String mappingId) {
        ActorResolver.requireActor(principal);
        return ContractViews.mappingRow(catalog.getMapping(mappingId));
    }

    /** 实例注册（FR-10）：凭证明文只经此接口落库，存密文（R-CON-03）。响应不含密文。 */
    @PostMapping("/instances")
    public Map<String, Object> register(Principal principal, @RequestBody InstanceRegisterRequest req) {
        String actor = ActorResolver.requireActor(principal);
        // D14：{} / 缺字段（envId 缺失炸 findByEnvId/落库、endpoint 缺失炸落库 NOT NULL）→ CR-400-001；
        // credential 可空（V1 列可空 + InstanceService 有 null 守卫）：缺省存 null，不加密。
        RequestChecks.require("envId", req.envId(), "endpoint", req.endpoint());
        byte[] cipher = (req.credential() == null || req.credential().isBlank())
                ? null : crypto.encrypt(req.credential());
        return ContractViews.instanceRow(instances.register(req.envId(), req.endpoint(), cipher, actor, null));
    }

    /**
     * 契约 #37：POST /instances/{id}/test（ADMIN）→ InstanceTestResult{instanceId,ok,latencyMs,message,testedAt}。
     * 路径参数兼容两义：先按 instanceId 找实例；找不到按 envId 处理（旧口径）。
     */
    @PostMapping("/instances/{id}/test")
    public Map<String, Object> test(Principal principal, @PathVariable String id) {
        ActorResolver.requireActor(principal);
        String envId = id;
        String instanceId = null;
        for (Instance inst : instances.listInstances()) {
            if (id.equals(inst.getInstanceId())) {
                envId = inst.getEnvId();
                instanceId = inst.getInstanceId();
                break;
            }
        }
        Env env = catalog.getEnv(envId);
        var report = port.test(new com.nacos.ncg.adapter.nacos.port.EnvId(env.getEnvCode()));
        Instance saved = instances.recordProbe(envId, report.reachable() && report.authenticated(),
                report.latencyMs(), null);
        if (instanceId == null && saved != null) instanceId = saved.getInstanceId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instanceId);
        body.put("envId", envId);
        body.put("ok", report.reachable() && report.authenticated());
        body.put("latencyMs", report.latencyMs());
        body.put("message", report.reachable() && report.authenticated()
                ? "connection ok" : "connection failed");
        body.put("testedAt", Instant.now());
        body.put("status", saved == null ? null : saved.getStatus());
        body.put("health", saved == null ? null : ContractViews.healthOf(saved.getStatus()));
        return body;
    }

    public record InstanceRegisterRequest(String envId, String endpoint, String credential) { }

    /** 应用配置清单（契约 #5：GET /apps/{appId}/configs，envId 可选过滤）。 */
    @GetMapping("/apps/{appId}/configs")
    public List<Map<String, Object>> appConfigs(@PathVariable String appId,
                                                @RequestParam(required = false) String envId) {
        List<ConfigMapping> list = envId == null
                ? catalog.listMappingsByApp(appId) : catalog.listMappings(appId, envId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigMapping m : list) rows.add(ContractViews.mappingRow(m));
        return rows;
    }

    /** 实例清单（契约 #36：GET /instances，ADMIN）。行不含密文。 */
    @GetMapping("/instances")
    public List<Map<String, Object>> listInstances() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instance inst : instances.listInstances()) rows.add(ContractViews.instanceRow(inst));
        return rows;
    }

    private List<String> envIdsOf(String appId) {
        List<String> envIds = new ArrayList<>();
        for (ConfigMapping m : catalog.listMappingsByApp(appId)) {
            if (m.getEnvId() != null && !envIds.contains(m.getEnvId())) envIds.add(m.getEnvId());
        }
        return envIds;
    }
}

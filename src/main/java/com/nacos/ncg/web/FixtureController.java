package com.nacos.ncg.web;

import com.nacos.ncg.adapter.nacos.fake.FakeNacosConfigAdapter;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.Subscriber;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联调夹具控制器（仅 local profile）：为 TC-20 等异常用例注入 FakeNacosConfigAdapter 的
 * FailureMode / 种子内容 / 直连变更 / 订阅方 / 环境下线。
 * 决策 D（INTEGRATION-REPORT）：不新增业务 API 面——仅 fake 适配器存在的 local profile 下可用；
 * real 模式此端点不存在（Spring @Profile("local") 不装配）。
 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/_fixture")
public class FixtureController {

    private final NacosConfigPort port;
    private final CatalogService catalog;
    private final com.nacos.ncg.audit.app.DirectScanService directScan;

    public FixtureController(NacosConfigPort port, CatalogService catalog,
                             com.nacos.ncg.audit.app.DirectScanService directScan) {
        this.port = port;
        this.catalog = catalog;
        this.directScan = directScan;
    }

    public record EnvReq(String envId, String envCode, String mode, Boolean down,
                         String namespace, String groupName, String dataId, String content,
                         String operator, List<Sub> subscribers) {}

    public record Sub(String address, String appName) {}

    private FakeNacosConfigAdapter adapter() {
        if (port instanceof FakeNacosConfigAdapter fake) return fake;
        throw new NcgException(NcgErrorCode.SYS_404_001, "fake adapter not active (ncg.nacos.mode != fake)");
    }

    private EnvIdKey envKey(EnvReq req) {
        Env env = null;
        if (req.envCode() != null && !req.envCode().isBlank()) {
            env = catalog.getEnvByCode(req.envCode());
        } else if (req.envId() != null && !req.envId().isBlank()) {
            env = catalog.getEnv(req.envId());
        }
        if (env == null) {
            throw new NcgException(NcgErrorCode.SYS_404_001, "env not found");
        }
        return new EnvIdKey(env.getEnvCode(), env.getEnvId());
    }

    private record EnvIdKey(String code, String id) {}

    /** 显式落盘 fake store（D16 持久化）：persist-path 未配置返回 saved:false＋reason。 */
    @PostMapping("/persist")
    public Map<String, Object> persist() {
        boolean saved = adapter().persistNow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", saved);
        if (!saved) {
            out.put("reason", "persist-path not configured (ncg.fake.persist-path)");
        }
        return out;
    }

    /** 设置故障模式：NONE|AUTH|UNAVAILABLE|INVALID|INDETERMINATE（TC-20 用 INDETERMINATE）。 */
    @PostMapping("/failure-mode")
    public Map<String, Object> failureMode(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        FakeNacosConfigAdapter.FailureMode m;
        try {
            m = FakeNacosConfigAdapter.FailureMode.valueOf(req.mode() == null ? "NONE" : req.mode());
        } catch (IllegalArgumentException e) {
            throw new NcgException(NcgErrorCode.CR_400_001, "unknown failure mode: " + req.mode());
        }
        adapter().setFailureMode(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()), m);
        return Map.of("envCode", k.code(), "mode", m.name());
    }

    /** 环境下线（预检 envDown→REL-503-001 用）。 */
    @PostMapping("/env-down")
    public Map<String, Object> envDown(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        boolean down = Boolean.TRUE.equals(req.down());
        adapter().setEnvDown(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()), down);
        return Map.of("envCode", k.code(), "down", down);
    }

    /** 种子内容（预置配置快照；mappingId 级联由调用方保证 dataId 与映射一致）。 */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        if (req.dataId() == null || req.dataId().isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "dataId is required");
        }
        adapter().seed(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()),
                new com.nacos.ncg.adapter.nacos.port.ConfigRef(
                        req.namespace() == null ? "" : req.namespace(),
                        req.groupName() == null ? "DEFAULT_GROUP" : req.groupName(),
                        req.dataId()),
                req.content() == null ? "" : req.content());
        return Map.of("envCode", k.code(), "dataId", req.dataId(), "seeded", true);
    }

    /** 直连变更注入（P-02/13 直连告警检测素材）。 */
    @PostMapping("/seed-external")
    public Map<String, Object> seedExternal(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        adapter().seedExternalChange(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()),
                new com.nacos.ncg.adapter.nacos.port.ConfigRef(
                        req.namespace() == null ? "" : req.namespace(),
                        req.groupName() == null ? "DEFAULT_GROUP" : req.groupName(),
                        req.dataId()),
                req.content() == null ? "" : req.content(),
                req.operator() == null ? "external" : req.operator());
        return Map.of("envCode", k.code(), "dataId", req.dataId(), "seeded", true);
    }

    /** 订阅方列表（影响面 IMP-409-001/未知订阅测试）。 */
    @PostMapping("/subscribers")
    public Map<String, Object> subscribers(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        List<Subscriber> subs = new ArrayList<>();
        if (req.subscribers() != null) {
            for (Sub s : req.subscribers()) subs.add(new Subscriber(s.address(), s.appName()));
        }
        adapter().setSubscribers(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()),
                new com.nacos.ncg.adapter.nacos.port.ConfigRef(
                        req.namespace() == null ? "" : req.namespace(),
                        req.groupName() == null ? "DEFAULT_GROUP" : req.groupName(),
                        req.dataId()),
                subs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("envCode", k.code());
        out.put("count", subs.size());
        return out;
    }

    /**
     * 触发一次直连扫描（联调 D11）：本地 fake 拓扑下 web 与 worker 是两个 JVM、
     * FakeNacosConfigAdapter 内存 store 互不可见，web 播的外部变更 worker 永远看不到；
     * 由 web 端触发同一条 {@code DirectScanService.scan} 检测链路（判定/入库/审计/告警完全一致）。
     * real 模式外部 Nacos 是共享状态，由 worker 定时扫描，本端点不存在（@Profile("local")）。
     */
    @PostMapping("/scan-direct")
    public Map<String, Object> scanDirect(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        directScan.scan(k.id(), k.code());
        return Map.of("envId", k.id(), "envCode", k.code(), "scanned", true);
    }

    /** 一键复位（失败模式 NONE + 环境恢复）。 */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody EnvReq req) {
        EnvIdKey k = envKey(req);
        adapter().setFailureMode(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()),
                FakeNacosConfigAdapter.FailureMode.NONE);
        adapter().setEnvDown(new com.nacos.ncg.adapter.nacos.port.EnvId(k.code()), false);
        return Map.of("envCode", k.code(), "reset", true);
    }
}

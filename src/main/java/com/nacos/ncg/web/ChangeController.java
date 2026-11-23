package com.nacos.ncg.web;

import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.Snapshot;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.change.app.ChangeDraftService;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.RiskLevel;
import com.nacos.ncg.shared.content.ConfigContentCodec;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更单生命周期（FR-02 / 流程 7.1，§6.2）：
 * 开单（草稿）→ 编辑 → 提交（冻结基线/影响面/风险）→ 审批 → 发布；
 * 草稿不写 Nacos；同映射仅一个活动单（CR-409-001）。
 *
 * t6 契约对齐（doc/ncg-api-contract.md §3.1/§3.4）：
 * - 缺口1：POST /changes 响应回显 {changeNo,status,riskLevel,version,format,itemCount}；
 * - 缺口4：items[] 与 content 互斥（同现或同缺 CR-400-001），UPDATE 结构化用 items
 *   （读当前 DataId 应用变更序列化整文件），CREATE/TEXT/整文件替换用 content；
 *   解析失败 CR-422-001（带行/列号，草稿不丢），未知后缀只读 CR-415-001；
 * - 基线创建时记录（TC-19：提交时复核漂移 → CR-412-001）。
 */
@RestController
@RequestMapping("/api/v1/changes")
public class ChangeController {

    private static final String SENSITIVE_MASK = "••••••";

    private final ChangeDraftService drafts;
    private final ChangeRequestRepository changes;
    private final CatalogService catalog;
    private final NacosConfigPort port;

    public ChangeController(ChangeDraftService drafts, ChangeRequestRepository changes,
                            CatalogService catalog, NacosConfigPort port) {
        this.drafts = drafts;
        this.changes = changes;
        this.catalog = catalog;
        this.port = port;
    }

    @PostMapping
    public Map<String, Object> open(Principal principal, @RequestBody OpenRequest req) {
        String actor = ActorResolver.requireActor(principal);
        // D14：{} / 缺字段（appId/envId/mappingId/changeType 缺失会炸在查询或落库路径）→ CR-400-001；
        // title/reason 仍由 drafts.open 的既有校验返回其业务文案（零回归）。
        RequestChecks.require("appId", req.appId(), "envId", req.envId(),
                "mappingId", req.mappingId(), "changeType", req.changeType());
        Prepared p = prepareContent(req.mappingId(), req.changeType(), req.items(), req.content(),
                null, req.baselineMd5());
        ChangeRequest cr = drafts.open(req.appId(), req.envId(), req.mappingId(), req.changeType(),
                req.title(), req.reason(), p.content(), actor, req.riskLevel(), null);
        if (req.plannedAt() != null && !req.plannedAt().isBlank()) {
            cr.setScheduleAt(Instant.parse(req.plannedAt()));
            cr = changes.save(cr);
        }
        return view(cr, p.format(), p.itemCount());
    }

    @GetMapping("/{changeNo}")
    public Map<String, Object> get(Principal principal, @PathVariable String changeNo) {
        ActorResolver.requireActor(principal);
        ChangeRequest cr = require(changeNo);
        return detail(cr);
    }

    @GetMapping
    public List<Map<String, Object>> list(Principal principal,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String appId,
                                          @RequestParam(required = false) String envId,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String createdBy,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        ActorResolver.requireActor(principal);
        var list = status == null || status.isBlank()
                ? changes.findAll()
                : changes.findByStatus(status);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var cr : list) {
            if (appId != null && !appId.isBlank() && !appId.equals(cr.getAppId())) continue;
            if (envId != null && !envId.isBlank() && !envId.equals(cr.getEnvId())) continue;
            if (createdBy != null && !createdBy.isBlank() && !createdBy.equals(cr.getSubmitterId())) continue;
            if (!kw.isEmpty()) {
                String hay = (cr.getChangeNo() + " " + cr.getTitle() + " " + cr.getReason()).toLowerCase();
                if (!hay.contains(kw)) continue;
            }
            out.add(row(cr));
        }
        out.sort((a, b) -> {
            Object ka = a.get("createdAt"), kb = b.get("createdAt");
            if (ka == null && kb == null) return 0;
            if (ka == null) return 1;
            if (kb == null) return -1;
            return kb.toString().compareTo(ka.toString());
        });
        if (page != null || size != null) {
            int sz = size == null ? 20 : Math.max(1, Math.min(100, size));
            int from = Math.max(0, (page == null ? 0 : page) * sz);
            int to = Math.min(out.size(), from + sz);
            out = from >= out.size() ? new ArrayList<>() : new ArrayList<>(out.subList(from, to));
        }
        return out;
    }

    @PutMapping("/{changeNo}")
    public Map<String, Object> edit(Principal principal, @PathVariable String changeNo,
                                    @RequestBody EditRequest req) {
        String actor = ActorResolver.requireActor(principal);
        ChangeRequest cr = require(changeNo);
        // 乐观锁（SYS-409-001）：version 不匹配拒编辑
        if (req.version() != null && cr.getVersion() != null
                && !req.version().equals(cr.getVersion())) {
            throw new NcgException(NcgErrorCode.SYS_409_001,
                    "version conflict: expected " + cr.getVersion() + ", got " + req.version());
        }
        String base = drafts.contentOf(cr);
        boolean metadataOnly = (req.items() == null || req.items().isEmpty()) && req.content() == null;
        Prepared p = metadataOnly
                ? new Prepared(null, formatOf(cr), -1)
                : prepareContent(cr.getMappingId(), cr.getChangeType(), req.items(), req.content(),
                base, null);
        String title = req.title() != null && !req.title().isBlank() ? req.title() : cr.getTitle();
        String reason = req.reason() != null ? req.reason() : cr.getReason();
        String content = metadataOnly ? null : (p.content() != null ? p.content() : base);
        ChangeRequest saved = drafts.edit(changeNo, title, reason, content, actor, null);
        if (req.plannedAt() != null) {
            saved.setScheduleAt(req.plannedAt().isBlank() ? null : Instant.parse(req.plannedAt()));
            saved = changes.save(saved);
        }
        return view(saved, p.format(), p.itemCount() >= 0 ? p.itemCount() : itemCountOf(saved, p.format()));
    }

    @PostMapping("/{changeNo}/submit")
    public Map<String, Object> submit(Principal principal, @PathVariable String changeNo,
                                      @RequestBody(required = false) SubmitRequest req) {
        String actor = ActorResolver.requireActor(principal);
        RiskLevel userLevel = req == null ? null : req.riskLevel();
        ChangeRequest cr = drafts.submit(changeNo, actor, userLevel, null);
        return detail(cr);
    }

    @PostMapping("/{changeNo}/cancel")
    public Map<String, Object> cancel(Principal principal, @PathVariable String changeNo,
                                      @RequestBody CancelRequest req) {
        String actor = ActorResolver.requireActor(principal);
        ChangeRequest cr = drafts.cancel(changeNo, req.reason(), actor, null);
        return detail(cr);
    }

    // ---------- 契约请求体 ----------

    public record ItemReq(String key, String value, String valueType) { }

    public record OpenRequest(String appId, String envId, String mappingId, String changeType,
                              String title, String reason, List<ItemReq> items, String content,
                              RiskLevel riskLevel, String baselineMd5, String plannedAt) { }

    public record EditRequest(Long version, String title, String reason,
                              List<ItemReq> items, String content, String plannedAt) { }

    public record SubmitRequest(RiskLevel riskLevel) { }
    public record CancelRequest(String reason) { }

    // ---------- 内部 ----------

    /** items/content 归一化：互斥校验、格式守卫、解析校验、items→content 序列化。 */
    private record Prepared(String content, String format, int itemCount) { }

    private Prepared prepareContent(String mappingId, String changeType,
                                    List<ItemReq> items, String content,
                                    String baseContent, String baselineMd5) {
        ConfigMapping mapping = catalog.getMapping(mappingId);
        if (!ConfigContentCodec.isKnownSuffix(mapping.getDataId())) {
            // 未知后缀（.ini 等）：只读浏览，发起/编辑变更拒写（TC-10 / MUST-NOT-11）
            throw new NcgException(NcgErrorCode.CR_415_001,
                    "该 DataId 格式不支持，只能只读浏览");
        }
        String format = mapping.getFormat() != null ? mapping.getFormat()
                : ConfigContentCodec.formatOfDataId(mapping.getDataId());
        boolean hasItems = items != null && !items.isEmpty();
        boolean hasContent = content != null && !content.isBlank();
        // 契约缺口4：items 与 content 互斥（同现或同缺 → CR-400-001）
        if (hasItems == hasContent) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    hasItems ? "items 与 content 不能同时提交" : "items 与 content 必须提供其一");
        }
        if (hasItems) {
            List<ConfigContentCodec.ItemUpdate> updates = new ArrayList<>();
            for (ItemReq it : items) {
                if (it.key() == null || it.key().isBlank()) {
                    throw new NcgException(NcgErrorCode.CR_400_001, "items[].key 必填");
                }
                String value = it.value();
                // 敏感项脱敏占位 → 保留既有值（缺口5「敏感项必须重新输入新值，不回显旧明文」防御）
                if (SENSITIVE_MASK.equals(value) && baseContent != null) {
                    var parsedBase = ConfigContentCodec.parse(format, baseContent);
                    for (var item : parsedBase.items()) {
                        if (item.key().equals(it.key())) {
                            value = item.value();
                            break;
                        }
                    }
                }
                updates.add(new ConfigContentCodec.ItemUpdate(it.key(), value));
            }
            String base = baseContent;
            if ((base == null || base.isBlank()) && !"CREATE".equals(changeType)) {
                // UPDATE 等：读当前 DataId 应用变更序列化整文件（缺口4）
                Env env = catalog.getEnv(mapping.getEnvId());
                try {
                    Snapshot live = port.read(new EnvId(env.getEnvCode()),
                            new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId()));
                    base = live.content();
                } catch (RuntimeException e) {
                    throw new NcgException(NcgErrorCode.CR_400_001,
                            "current config not readable for " + changeType + ": " + e.getMessage());
                }
            }
            String merged;
            if (base == null || base.isBlank()) {
                // CREATE：按 items 序列化整文件
                List<ConfigContentCodec.Item> out = new ArrayList<>();
                for (var u : updates) {
                    out.add(new ConfigContentCodec.Item(u.key(), u.value(),
                            ConfigContentCodec.valueTypeOf(u.value()),
                            ConfigContentCodec.isSensitiveKey(u.key())));
                }
                merged = ConfigContentCodec.serialize(format, out);
            } else {
                merged = ConfigContentCodec.applyItems(format, base, updates);
            }
            return new Prepared(merged, format, updates.size());
        }
        // content 路径：结构化格式必须可解析（TC-11 解析失败 CR-422-001，草稿不丢）
        if (!"TEXT".equals(format)) {
            var parsed = ConfigContentCodec.parse(format, content);
            if (parsed.parseError() != null) {
                throw new NcgException(NcgErrorCode.CR_422_001,
                        "配置内容解析失败（第 " + parsed.parseError().line() + " 行）",
                        Map.of("line", parsed.parseError().line(),
                                "column", parsed.parseError().column()));
            }
        }
        return new Prepared(content, format, 1);
    }

    private ChangeRequest require(String changeNo) {
        return changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
    }

    /** 缺口1 响应视图：{changeNo,status,riskLevel,version,format,itemCount} + 基础字段。 */
    private Map<String, Object> view(ChangeRequest cr, String format, int itemCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changeNo", cr.getChangeNo());
        m.put("status", cr.getStatus());
        m.put("riskLevel", String.valueOf(cr.getRiskLevel()));
        m.put("version", cr.getVersion());
        m.put("format", format);
        m.put("itemCount", itemCount);
        m.put("changeType", cr.getChangeType());
        m.put("appId", cr.getAppId());
        m.put("envId", cr.getEnvId());
        m.put("mappingId", cr.getMappingId());
        m.put("title", cr.getTitle());
        m.put("reason", cr.getReason());
        m.put("createdAt", cr.getCreatedAt());
        m.put("updatedAt", cr.getUpdatedAt());
        return m;
    }

    /** 详情（契约 ChangeDetail）：items/content 按敏感口径脱敏。 */
    private Map<String, Object> detail(ChangeRequest cr) {
        String format = formatOf(cr);
        String content = drafts.contentOf(cr);
        Map<String, Object> m = row(cr);
        m.put("version", cr.getVersion());
        m.put("format", format);
        ConfigMapping mapping = safeMapping(cr.getMappingId());
        boolean fileSensitive = mapping != null && mapping.isSensitive();
        if (content != null && !content.isBlank()) {
            var parsed = ConfigContentCodec.parse(format, content);
            if (parsed.parseError() == null && !"TEXT".equals(format)) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (var item : parsed.items()) {
                    boolean sensitive = fileSensitive || item.sensitive();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", item.key());
                    row.put("value", sensitive ? SENSITIVE_MASK : item.value());
                    row.put("valueType", item.valueType());
                    row.put("sensitive", sensitive);
                    items.add(row);
                }
                m.put("items", items);
                m.put("itemCount", items.size());
            } else if ("TEXT".equals(format)) {
                m.put("content", fileSensitive ? SENSITIVE_MASK : content);
                m.put("itemCount", 1);
            } else {
                // 解析失败：返回原 content 只读（TC-11 草稿不丢）
                m.put("content", content);
                m.put("parseError", Map.of(
                        "line", parsed.parseError().line(),
                        "column", parsed.parseError().column(),
                        "message", parsed.parseError().message()));
            }
        }
        return m;
    }

    /** 列表行（契约 ChangeListItem）。 */
    private Map<String, Object> row(ChangeRequest cr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changeNo", cr.getChangeNo());
        m.put("title", cr.getTitle());
        m.put("appId", cr.getAppId());
        m.put("envId", cr.getEnvId());
        m.put("mappingId", cr.getMappingId());
        ConfigMapping mapping = safeMapping(cr.getMappingId());
        m.put("dataId", mapping != null ? mapping.getDataId() : null);
        m.put("changeType", cr.getChangeType());
        m.put("status", cr.getStatus());
        m.put("riskLevel", String.valueOf(cr.getRiskLevel()));
        m.put("createdBy", cr.getSubmitterId());
        m.put("submitterId", cr.getSubmitterId()); // 旧字段
        m.put("createdAt", cr.getCreatedAt());
        m.put("updatedAt", cr.getUpdatedAt());
        m.put("plannedAt", cr.getScheduleAt());
        m.put("scheduleAt", cr.getScheduleAt()); // 旧字段
        m.put("reason", cr.getReason());
        m.put("baselineMd5", cr.getBaselineMd5());
        return m;
    }

    private String formatOf(ChangeRequest cr) {
        ConfigMapping mapping = safeMapping(cr.getMappingId());
        if (mapping != null && mapping.getFormat() != null) {
            return mapping.getFormat();
        }
        return mapping != null ? ConfigContentCodec.formatOfDataId(mapping.getDataId()) : "TEXT";
    }

    private int itemCountOf(ChangeRequest cr, String format) {
        String content = drafts.contentOf(cr);
        if (content == null || content.isBlank()) {
            return 0;
        }
        if ("TEXT".equals(format)) {
            return 1;
        }
        var parsed = ConfigContentCodec.parse(format, content);
        return parsed.parseError() == null ? parsed.items().size() : 0;
    }

    private ConfigMapping safeMapping(String mappingId) {
        try {
            return catalog.getMapping(mappingId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 影响面快照（§6.2 GET /changes/{changeNo}/impact；IMP-409-001 缺失阻断）。 */
    @GetMapping("/{changeNo}/impact")
    public Map<String, Object> impact(@PathVariable String changeNo, Principal principal) {
        ActorResolver.requireActor(principal);
        ChangeRequest cr = require(changeNo);
        if (cr.getImpactSnapshot() == null || cr.getImpactSnapshot().json() == null
                || cr.getImpactSnapshot().json().isBlank()) {
            throw new NcgException(NcgErrorCode.IMP_409_001,
                    "impact snapshot missing (frozen at submit)");
        }
        return Map.of("changeNo", changeNo, "impactSnapshot", cr.getImpactSnapshot().json(),
                "riskLevel", String.valueOf(cr.getRiskLevel()),
                "riskFactors", cr.getRiskFactors() == null ? "" : cr.getRiskFactors().json());
    }

}

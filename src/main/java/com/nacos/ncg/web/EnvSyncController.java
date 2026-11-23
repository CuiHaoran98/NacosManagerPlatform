package com.nacos.ncg.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.envsync.app.EnvSyncService;
import com.nacos.ncg.envsync.domain.EnvSyncSnapshot;
import com.nacos.ncg.envsync.repo.EnvSyncSnapshotRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨环境同步（契约 #33-35）：compare / apply / GET 快照。
 * body 与响应按契约口径（sourceEnvId/targetEnvId/mappingIds/title/reason、diffs[{mappingId,dataId,same,sourceMd5,targetMd5}]），
 * 兼容旧字段名（sourceEnvCode/targetEnvCode）；环境入参可传环境 id 或 code。
 */
@RestController
@RequestMapping("/api/v1/env-sync")
public class EnvSyncController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EnvSyncService envSyncService;
    private final EnvSyncSnapshotRepository snapshots;
    private final CatalogService catalog;
    private final ConfigMappingRepository mappings;

    public EnvSyncController(EnvSyncService envSyncService, EnvSyncSnapshotRepository snapshots,
                             CatalogService catalog, ConfigMappingRepository mappings) {
        this.envSyncService = envSyncService;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.mappings = mappings;
    }

    /** 契约 #33：对比（VIEW）。源/目标环境可传 id 或 code。 */
    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody CompareRequest req, Principal principal) {
        String sourceCode = envCodeOf(firstNonBlank(req.sourceEnvId(), req.sourceEnvCode()));
        String targetCode = envCodeOf(firstNonBlank(req.targetEnvId(), req.targetEnvCode()));
        String targetEnvId = envIdOf(firstNonBlank(req.targetEnvId(), req.targetEnvCode()));
        EnvSyncSnapshot snap = envSyncService.compare(sourceCode, targetCode,
                req.appId(), targetEnvId,
                principal == null ? "system" : principal.getName(), null);
        return snapshotBody(snap, req.dataId());
    }

    /** 契约 #35：读快照。 */
    @GetMapping("/{snapshotId}")
    public Map<String, Object> get(@PathVariable String snapshotId,
                                   @RequestParam(required = false) String dataId) {
        EnvSyncSnapshot snap = snapshots.findById(snapshotId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "snapshot not found"));
        return snapshotBody(snap, dataId);
    }

    /** 契约 #34：对勾选差异项在目标环境建 SYNC 变更单（EDIT）。 */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody ApplyRequest req, Principal principal) {
        if (req.snapshotId() == null || req.snapshotId().isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "snapshotId is required");
        }
        String targetEnvId = envIdOf(req.targetEnvId());
        Set<String> selected = null;
        if (req.mappingIds() != null) {
            java.util.Set<String> picked = new java.util.LinkedHashSet<>();
            for (String id : req.mappingIds()) {
                if (id != null && !id.isBlank()) picked.add(id);
            }
            selected = picked.isEmpty() ? null : picked;
        }
        List<String> changeNos = envSyncService.propose(req.snapshotId(), req.appId(), targetEnvId,
                selected, req.title(), req.reason(),
                principal == null ? "system" : principal.getName(), null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", req.snapshotId());
        body.put("changeNos", changeNos);
        body.put("createdChangeNos", changeNos); // 旧字段名兼容
        return body;
    }

    /** 旧 propose 入口（契约外，保留）。 */
    @PostMapping("/{snapshotId}/propose")
    public Map<String, Object> propose(@PathVariable String snapshotId,
                                       @RequestBody ProposeRequest req, Principal principal) {
        String targetEnvId = envIdOf(req.targetEnvId());
        List<String> changeNos = envSyncService.propose(snapshotId, req.appId(), targetEnvId,
                principal == null ? "system" : principal.getName(), null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snapshotId);
        body.put("changeNos", changeNos);
        body.put("createdChangeNos", changeNos);
        return body;
    }

    // ---------- 形状 ----------

    private Map<String, Object> snapshotBody(EnvSyncSnapshot snap, String dataIdFilter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snap.getSnapshotId());
        body.put("sourceEnvId", snap.getSourceEnv()); // 快照按 code 落库；请求侧同值回显
        body.put("targetEnvId", snap.getTargetEnv());
        body.put("sourceEnv", snap.getSourceEnv());
        body.put("targetEnv", snap.getTargetEnv());
        body.put("collectedAt", snap.getCreatedAt());
        body.put("createdAt", snap.getCreatedAt());
        body.put("configCount", snap.getConfigCount());
        body.put("expiresAt", snap.getExpiresAt());
        body.put("createdBy", snap.getCreatedBy());
        List<Map<String, Object>> diffs = new ArrayList<>();
        try {
            Map<String, Object> root = MAPPER.readValue(snap.getDiffSummary().json(),
                    new TypeReference<Map<String, Object>>() {});
            Object raw = root.get("diffs");
            if (raw instanceof List<?> rows) {
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> row)) continue;
                    String dataId = String.valueOf(row.get("dataId"));
                    if (dataIdFilter != null && !dataIdFilter.isBlank() && !dataIdFilter.equals(dataId)) {
                        continue;
                    }
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("mappingId", resolveMappingId(snap.getTargetEnv(), dataId));
                    diff.put("dataId", dataId);
                    diff.put("same", false);
                    diff.put("sourceMd5", row.get("sourceMd5"));
                    diff.put("targetMd5", row.get("targetMd5"));
                    diff.put("status", row.get("status"));
                    diffs.add(diff);
                }
            }
        } catch (Exception e) {
            // diffSummary 解析失败：只回空差异，不阻断读取
        }
        body.put("diffs", diffs);
        body.put("diffSummary", snap.getDiffSummary() == null ? null : snap.getDiffSummary().json());
        return body;
    }

    private String resolveMappingId(String targetEnvCodeOrId, String dataId) {
        try {
            String targetEnvId = envIdOf(targetEnvCodeOrId);
            return mappings.findByStatusOrderByDataIdAsc("MANAGED").stream()
                    .filter(m -> targetEnvId.equals(m.getEnvId()) && m.getDataId().equals(dataId))
                    .map(ConfigMapping::getMappingId)
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- 入参解析（id/code 两可） ----------

    private String envCodeOf(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "env is required");
        }
        try {
            return catalog.getEnv(idOrCode).getEnvCode();
        } catch (RuntimeException ignored) {
            // fallthrough
        }
        try {
            catalog.getEnvByCode(idOrCode);
            return idOrCode;
        } catch (RuntimeException ignored) {
            // fallthrough
        }
        throw new NcgException(NcgErrorCode.SYS_404_001, "env not found: " + idOrCode);
    }

    private String envIdOf(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "env is required");
        }
        try {
            catalog.getEnv(idOrCode);
            return idOrCode;
        } catch (RuntimeException ignored) {
            // fallthrough
        }
        try {
            return catalog.getEnvByCode(idOrCode).getEnvId();
        } catch (RuntimeException ignored) {
            // fallthrough
        }
        throw new NcgException(NcgErrorCode.SYS_404_001, "env not found: " + idOrCode);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    // ---------- 请求体 ----------

    /** 契约：{sourceEnvId,targetEnvId,appId?,dataId?}；兼容 sourceEnvCode/targetEnvCode。 */
    public record CompareRequest(String sourceEnvId, String targetEnvId,
                                 String sourceEnvCode, String targetEnvCode,
                                 String appId, String dataId) {}

    /** 契约：{snapshotId,targetEnvId,mappingIds,title?,reason?}；兼容 appId。 */
    public record ApplyRequest(String snapshotId, String targetEnvId, List<String> mappingIds,
                               String appId, String title, String reason) {}

    /** 旧 propose 入参。 */
    public record ProposeRequest(String appId, String targetEnvId) {}
}

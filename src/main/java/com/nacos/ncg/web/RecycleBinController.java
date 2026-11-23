package com.nacos.ncg.web;

import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.RecycleBinItem;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.catalog.repo.RecycleBinItemRepository;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.release.app.RollbackService;
import com.nacos.ncg.release.domain.ReleaseRecord;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收站 REST（契约 #45/#46）：列表（PageResult + RecycleBinItem{appId,envId,dataId,expireAt} 别名）、
 * 恢复（body {onConflict:OVERRIDE_AND_REAPPROVE|ABANDON,itemVersion?}）。
 * 同名冲突无决策时 RB-409-001 只给「覆盖并重新审批/放弃」两个选项（TC-22 / MUST-NOT-06）；
 * 超期 RB-410-001（服务层判定）。
 */
@RestController
@RequestMapping("/api/v1/recycle-bin")
public class RecycleBinController {

    private final RecycleBinItemRepository recycleBin;
    private final ConfigMappingRepository mappings;
    private final RollbackService rollbackService;

    public RecycleBinController(RecycleBinItemRepository recycleBin, ConfigMappingRepository mappings,
                                RollbackService rollbackService) {
        this.recycleBin = recycleBin;
        this.mappings = mappings;
        this.rollbackService = rollbackService;
    }

    /** 契约 #45：GET /recycle-bin（PUBLISH）。参数 appId/envId/keyword/page/size；status 兼容旧口径默认 PENDING。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String appId,
                                    @RequestParam(required = false) String envId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(defaultValue = "PENDING") String status) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RecycleBinItem item : recycleBin.findByRestoreStatusOrderByDeletedAtDesc(status)) {
            Map<String, Object> row = row(item);
            if (appId != null && !appId.isBlank() && !appId.equals(row.get("appId"))) continue;
            if (envId != null && !envId.isBlank() && !envId.equals(row.get("envId"))) continue;
            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.toLowerCase();
                String dataId = String.valueOf(row.getOrDefault("dataId", ""));
                if (!dataId.toLowerCase().contains(kw)) continue;
            }
            rows.add(row);
        }
        int safeSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        int safePage = Math.max(0, page);
        int from = Math.min(safePage * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.subList(from, to));
        body.put("total", rows.size());
        body.put("page", safePage);
        body.put("size", safeSize);
        return body;
    }

    /** 契约 #46：恢复。body 可选 {onConflict,itemVersion?}；冲突未决策→RB-409-001 只返两选项。 */
    @PostMapping("/{itemId}/restore")
    public Map<String, Object> restore(@PathVariable String itemId,
                                       @RequestBody(required = false) RestoreRequest req,
                                       Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        RecycleBinItem item = recycleBin.findById(itemId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "recycle item not found: " + itemId));
        ConfigMapping mapping = mappings.findById(item.getMappingId()).orElse(null);
        boolean clash = mapping != null && mappings.existsByNamespaceAndGroupNameAndDataIdAndStatusNot(
                mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId(), "DELETED");
        String onConflict = req == null ? null : req.onConflict();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemId", itemId);

        if (clash) {
            if (onConflict == null || onConflict.isBlank()) {
                // TC-22 / MUST-NOT-06：只返「覆盖并重新审批/放弃」
                throw new NcgException(NcgErrorCode.RB_409_001,
                        "同名配置已存在：请选择「覆盖并重新审批」或「放弃」",
                        Map.of("options", List.of("OVERRIDE_AND_REAPPROVE", "ABANDON"),
                                "dataId", mapping.getDataId()));
            }
            switch (onConflict) {
                case "ABANDON" -> {
                    body.put("conflict", true);
                    body.put("abandoned", true);
                    body.put("changeNo", null);
                    return body;
                }
                case "OVERRIDE_AND_REAPPROVE" -> {
                    ChangeRequest cr = rollbackService.restoreAsChange(itemId, actorId, null);
                    body.put("conflict", true);
                    body.put("changeNo", cr.getChangeNo());
                    body.put("restoreStatus", "RESTORED");
                    return body;
                }
                default -> throw new NcgException(NcgErrorCode.CR_400_001,
                        "onConflict must be OVERRIDE_AND_REAPPROVE or ABANDON");
            }
        }

        ReleaseRecord record = rollbackService.restore(itemId, actorId, null);
        body.put("conflict", false);
        body.put("changeNo", null);
        body.put("restoreStatus", "RESTORED");
        if (record != null) {
            body.put("releaseId", record.getReleaseId());
            body.put("outcome", record.getOutcome());
            body.put("readbackMd5", record.getReadbackMd5());
        } else {
            body.put("releaseId", null);
            body.put("outcome", "NO_CONTENT_REWRITE");
        }
        return body;
    }

    // ---------- 形状 ----------

    private Map<String, Object> row(RecycleBinItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemId", item.getItemId());
        body.put("mappingId", item.getMappingId());
        body.put("deletedAt", item.getDeletedAt());
        body.put("deletedBy", item.getDeletedBy());
        body.put("purgeAt", item.getPurgeAt());
        body.put("expireAt", item.getPurgeAt()); // 契约别名（差异4）
        body.put("restoreStatus", item.getRestoreStatus());
        body.put("createdAt", item.getCreatedAt());
        body.put("updatedAt", item.getUpdatedAt());
        ConfigMapping mapping = mappings.findById(item.getMappingId()).orElse(null);
        body.put("appId", mapping == null ? null : mapping.getAppId());
        body.put("envId", mapping == null ? null : mapping.getEnvId());
        body.put("dataId", mapping == null ? null : mapping.getDataId());
        return body;
    }

    /** 契约：{onConflict:'OVERRIDE_AND_REAPPROVE'|'ABANDON',itemVersion?}。 */
    public record RestoreRequest(String onConflict, Long itemVersion) {}
}

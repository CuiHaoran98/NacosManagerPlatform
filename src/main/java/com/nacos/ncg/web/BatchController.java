package com.nacos.ncg.web;

import com.nacos.ncg.batch.app.BatchDraftService;
import com.nacos.ncg.batch.app.BatchService;
import com.nacos.ncg.batch.domain.Batch;
import com.nacos.ncg.shared.content.ConfigContentCodec;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量 REST（FR-12）：创建批量；执行由 Worker 异步进行。
 *
 * t6 契约对齐（§2 #29-32 / §3.5 缺口5）：POST /batches 支持两种模式——
 * - items[] 模式（契约）：{title,reason,items:[{mappingId,items[]}]} → 每个 DataId
 *   生成 ChangeRequest 子草稿，响应 {batchId,childCount,status:"DRAFT",items[]}；
 * - changeNos 模式（既有）：组批发布已 APPROVED 变更单（mode=RELEASE，行为不变）。
 * PUT /batches/{batchId}/items/{itemId} 编辑子草稿（version 乐观锁，SYS-409-001 已提交拒编辑）。
 */
@RestController
@RequestMapping("/api/v1")
public class BatchController {

    private final BatchService batchService;
    private final BatchDraftService draftService;
    private final com.nacos.ncg.release.app.ReleaseService releaseService;

    public BatchController(BatchService batchService, BatchDraftService draftService,
                           com.nacos.ncg.release.app.ReleaseService releaseService) {
        this.batchService = batchService;
        this.draftService = draftService;
        this.releaseService = releaseService;
    }

    public record ItemReq(String key, String value, String valueType) { }
    public record GroupReq(String mappingId, List<ItemReq> items) { }
    public record CreateBatchRequest(String title, String reason, List<GroupReq> items,
                                     List<String> changeNos) {}

    @PostMapping("/batches")
    public Map<String, Object> create(@RequestBody CreateBatchRequest req, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        if (req.items() != null && !req.items().isEmpty()) {
            // 契约缺口5：子草稿批量
            List<BatchDraftService.Group> groups = new ArrayList<>();
            for (GroupReq g : req.items()) {
                List<ConfigContentCodec.ItemUpdate> updates = new ArrayList<>();
                if (g.items() != null) {
                    for (ItemReq it : g.items()) {
                        updates.add(new ConfigContentCodec.ItemUpdate(it.key(), it.value()));
                    }
                }
                groups.add(new BatchDraftService.Group(g.mappingId(), updates));
            }
            Batch batch = draftService.createDraftBatch(req.title(), req.reason(), groups, actorId, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("batchId", batch.getBatchId());
            out.put("childCount", batch.getTotalCount());
            out.put("status", batch.getStatus());
            out.put("mode", batch.getMode());
            out.put("items", draftService.itemRows(batch.getBatchId())); // 差异6：子项 itemId 获取路径
            return out;
        }
        // 既有模式：组批发布
        Batch batch = batchService.createBatch(req.changeNos(), actorId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch.getBatchId());
        out.put("childCount", batch.getTotalCount());
        out.put("status", batch.getStatus());
        out.put("mode", batch.getMode() == null ? "RELEASE" : batch.getMode());
        return out;
    }

    /** 子草稿编辑（§2 #30）：{version,items[]} → {itemId,status,version,itemCount}。 */
    @PutMapping("/batches/{batchId}/items/{itemId}")
    public Map<String, Object> updateItem(@PathVariable String batchId, @PathVariable String itemId,
                                          @RequestBody UpdateItemRequest req, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        List<ConfigContentCodec.ItemUpdate> updates = new ArrayList<>();
        if (req.items() != null) {
            for (ItemReq it : req.items()) {
                updates.add(new ConfigContentCodec.ItemUpdate(it.key(), it.value()));
            }
        }
        return draftService.updateDraftItem(batchId, itemId, req.version(), updates, actorId, null);
    }

    public record UpdateItemRequest(Long version, List<ItemReq> items) { }

    /** 批量执行（§6.2 POST /batches/{batchId}/submit；异步语义由 Worker 承担，此处同步驱动同一入口）。 */
    @PostMapping("/batches/{batchId}/submit")
    public Map<String, Object> submit(@PathVariable String batchId, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        Batch batch = batchService.getBatch(batchId);
        if (batch != null && "DRAFT".equals(batch.getMode())) {
            return draftService.submitDrafts(batchId, actorId, null);
        }
        batchService.runBatch(batchId, actorId, (changeNo, sysActor) -> {
            releaseService.release(changeNo, "batch-" + batchId, sysActor, null, null, "BATCH", sysActor);
            return changeNo;
        });
        return Map.of("batchId", batchId, "submitted", true);
    }

    /** 失败子项重试（§6.2 POST /batches/{batchId}/retry；成功子项永不重发 R-SCH-06）。 */
    @PostMapping("/batches/{batchId}/retry")
    public Map<String, Object> retry(@PathVariable String batchId, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        Batch batch = batchService.getBatch(batchId);
        if (batch != null && "DRAFT".equals(batch.getMode())) {
            return draftService.retryDrafts(batchId, actorId, null);
        }
        batchService.retryFailed(batchId, actorId, (changeNo, sysActor) -> {
            releaseService.release(changeNo, "batch-" + batchId, sysActor, null, null, "BATCH", sysActor);
            return changeNo;
        });
        return Map.of("batchId", batchId, "retried", true);
    }

}

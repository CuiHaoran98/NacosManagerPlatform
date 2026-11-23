package com.nacos.ncg.batch.app;

import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.port.Snapshot;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.batch.domain.Batch;
import com.nacos.ncg.batch.domain.BatchItem;
import com.nacos.ncg.batch.repo.BatchItemRepository;
import com.nacos.ncg.batch.repo.BatchRepository;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.change.app.ChangeDraftService;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.content.ConfigContentCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子草稿批量（契约缺口5 / §3.5）：POST /batches {title,reason,items:[{mappingId,items[]}]}
 * 每个 DataId 生成一个 ChangeRequest 子草稿（同 DataId 多 key 合并一个子项）；
 * PUT /batches/{batchId}/items/{itemId} 编辑子草稿（version 乐观锁递增、Diff 重算）；
 * 子项或父单已提交 → SYS-409-001；敏感项不回显旧明文（脱敏占位原样保留旧值）。
 *
 * 与 BatchService 的组批发布模型（mode=RELEASE）并存；既有方法签名不变（测试契约）。
 */
@Service
public class BatchDraftService {

    private static final String SENSITIVE_MASK = "••••••";

    private final BatchRepository batches;
    private final BatchItemRepository items;
    private final ChangeRequestRepository changes;
    private final ChangeDraftService drafts;
    private final CatalogService catalog;
    private final NacosConfigPort port;
    private final AuditWriter auditWriter;
    private final TransactionTemplate tx;
    private final int maxItems;

    public BatchDraftService(BatchRepository batches, BatchItemRepository items,
                             ChangeRequestRepository changes, ChangeDraftService drafts,
                             CatalogService catalog, NacosConfigPort port,
                             AuditWriter auditWriter, PlatformTransactionManager txm,
                             @Value("${ncg.batch-max-items:50}") int maxItems) {
        this.batches = batches;
        this.items = items;
        this.changes = changes;
        this.drafts = drafts;
        this.catalog = catalog;
        this.port = port;
        this.auditWriter = auditWriter;
        this.tx = new TransactionTemplate(txm);
        this.maxItems = maxItems;
    }

    /** 一个 DataId 的变更键值组。 */
    public record Group(String mappingId, List<ConfigContentCodec.ItemUpdate> items) { }

    /**
     * 创建子草稿批量（TC-13/14/15）。校验全部前置，任何失败不落父单/子项。
     */
    public Batch createDraftBatch(String title, String reason, List<Group> groups,
                                  String operatorId, String traceId) {
        if (title == null || title.isBlank()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "title is required");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new NcgException(NcgErrorCode.CR_400_001,
                    "reason must be at least 10 characters");
        }
        if (groups == null || groups.isEmpty()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "未填任何新值");
        }
        for (Group g : groups) {
            if (g.items() == null || g.items().isEmpty()) {
                // TC-14：子项 items 为空 → CR-400-001，不创建父单/子项
                throw new NcgException(NcgErrorCode.CR_400_001, "未填任何新值");
            }
        }
        if (groups.size() > maxItems) {
            // TC-15：>50 个 DataId → BAT-413-001，不创建父单
            throw new NcgException(NcgErrorCode.BAT_413_001, "批量数量超过上限");
        }

        // 归并同一 mappingId（同一 DataId 多 key 合并为一个子项）
        Map<String, List<ConfigContentCodec.ItemUpdate>> merged = new LinkedHashMap<>();
        for (Group g : groups) {
            merged.computeIfAbsent(g.mappingId(), k -> new ArrayList<>()).addAll(g.items());
        }

        // 前置校验：映射存在、后缀可写、无活动变更单；并预计算子草稿内容（解析失败在此抛出）
        record PreparedChild(ConfigMapping mapping, String changeType, String content, int itemCount) { }
        List<PreparedChild> prepared = new ArrayList<>();
        for (var entry : merged.entrySet()) {
            ConfigMapping mapping = catalog.getMapping(entry.getKey());
            if (!ConfigContentCodec.isKnownSuffix(mapping.getDataId())) {
                throw new NcgException(NcgErrorCode.CR_415_001, "该 DataId 格式不支持，只能只读浏览");
            }
            var active = changes.findFirstByMappingIdAndStatusIn(entry.getKey(),
                    com.nacos.ncg.change.app.ChangeDraftService.ACTIVE_STATES);
            if (active.isPresent()) {
                throw new NcgException(NcgErrorCode.CR_409_001,
                        "该配置已有进行中变更单", Map.of("changeNo", active.get().getChangeNo()));
            }
            String format = mapping.getFormat() != null ? mapping.getFormat()
                    : ConfigContentCodec.formatOfDataId(mapping.getDataId());
            String base = null;
            String changeType = "CREATE";
            try {
                Env env = catalog.getEnv(mapping.getEnvId());
                Snapshot live = port.read(new EnvId(env.getEnvCode()),
                        new ConfigRef(mapping.getNamespace(), mapping.getGroupName(), mapping.getDataId()));
                base = live.content();
                changeType = "UPDATE";
            } catch (RuntimeException e) {
                // 当前配置不存在 → CREATE，按 items 序列化整文件
            }
            String content;
            if (base == null || base.isBlank()) {
                List<ConfigContentCodec.Item> out = new ArrayList<>();
                for (var u : entry.getValue()) {
                    out.add(new ConfigContentCodec.Item(u.key(), u.value(),
                            ConfigContentCodec.valueTypeOf(u.value()),
                            ConfigContentCodec.isSensitiveKey(u.key())));
                }
                content = ConfigContentCodec.serialize(format, out);
            } else {
                content = ConfigContentCodec.applyItems(format, base, entry.getValue());
            }
            prepared.add(new PreparedChild(mapping, changeType, content, entry.getValue().size()));
        }

        Instant now = Instant.now();
        return tx.execute(status -> {
            String batchId = Ulid.generate();
            Batch batch = new Batch();
            batch.setBatchId(batchId);
            batch.setParentChangeNo("CR-" + LocalDate.now(ZoneOffset.UTC).format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-B" + batchId.substring(0, 8));
            batch.setStatus("DRAFT");
            batch.setMode("DRAFT");
            batch.setTotalCount(prepared.size());
            batch.setSucceededCount(0);
            batch.setFailedCount(0);
            batch.setCreatedBy(operatorId);
            batch.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
            batch.setCreatedAt(now);
            batch.setUpdatedAt(now);
            batches.save(batch);

            for (PreparedChild pc : prepared) {
                ChangeRequest child = drafts.open(
                        pc.mapping().getAppId(), pc.mapping().getEnvId(), pc.mapping().getMappingId(),
                        pc.changeType(), title, reason, pc.content(), operatorId, null, traceId);
                BatchItem item = new BatchItem();
                item.setItemId(Ulid.generate());
                item.setBatchId(batchId);
                item.setChangeNo(child.getChangeNo());
                item.setStatus("PENDING");
                items.save(item);
            }
            auditWriter.append(new AuditWriter.Command(
                    ActorType.USER, operatorId, null, AuditAction.BATCH_CREATE,
                    "BATCH", batchId, null, null, true, null, null, traceId));
            return batch;
        });
    }

    /** 子草稿编辑（TC-16/17）：version 乐观锁、Diff 重算（sha256 随内容更新）。 */
    public Map<String, Object> updateDraftItem(String batchId, String itemId, Long version,
                                               List<ConfigContentCodec.ItemUpdate> updates,
                                               String operatorId, String traceId) {
        Batch batch = requireBatch(batchId);
        if (!"DRAFT".equals(batch.getMode())) {
            throw new NcgException(NcgErrorCode.SYS_409_001, "batch item is not editable");
        }
        BatchItem item = items.findById(itemId)
                .filter(i -> i.getBatchId().equals(batchId))
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001,
                        "batch item not found: " + itemId));
        ChangeRequest child = changes.findById(item.getChangeNo())
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "child change not found"));
        if (!"DRAFT".equals(child.getStatus())) {
            // TC-17：子项已提交 → SYS-409-001，子项内容不变
            throw new NcgException(NcgErrorCode.SYS_409_001, "batch item already submitted");
        }
        if (version != null && child.getVersion() != null && !version.equals(child.getVersion())) {
            throw new NcgException(NcgErrorCode.SYS_409_001,
                    "version conflict: expected " + child.getVersion() + ", got " + version);
        }
        if (updates == null || updates.isEmpty()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "未填任何新值");
        }
        ConfigMapping mapping = catalog.getMapping(child.getMappingId());
        String format = mapping.getFormat() != null ? mapping.getFormat()
                : ConfigContentCodec.formatOfDataId(mapping.getDataId());
        String base = drafts.contentOf(child);
        List<ConfigContentCodec.ItemUpdate> effective = new ArrayList<>();
        for (var u : updates) {
            String value = u.value();
            if (SENSITIVE_MASK.equals(value) && base != null) {
                // 敏感项脱敏占位 → 保留旧值（必须重新输入新值，不回显旧明文）
                var parsedBase = ConfigContentCodec.parse(format, base);
                for (var item2 : parsedBase.items()) {
                    if (item2.key().equals(u.key())) {
                        value = item2.value();
                        break;
                    }
                }
            }
            effective.add(new ConfigContentCodec.ItemUpdate(u.key(), value));
        }
        String content = ConfigContentCodec.applyItems(format, base, effective);
        ChangeRequest saved = drafts.edit(child.getChangeNo(), null, null, content, operatorId, traceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", itemId);
        out.put("status", "DRAFT");
        out.put("version", saved.getVersion());
        out.put("itemCount", effective.size());
        out.put("changeNo", child.getChangeNo());
        return out;
    }

    /** 提交父单（子草稿 → PENDING_APPROVAL）；部分成功 PARTIAL_SUCCESS。 */
    public Map<String, Object> submitDrafts(String batchId, String operatorId, String traceId) {
        return runDrafts(batchId, operatorId, traceId, false);
    }

    /** 重试失败子项（成功子项永不重发 R-SCH-06）。 */
    public Map<String, Object> retryDrafts(String batchId, String operatorId, String traceId) {
        return runDrafts(batchId, operatorId, traceId, true);
    }

    private Map<String, Object> runDrafts(String batchId, String operatorId, String traceId, boolean retryOnly) {
        Batch batch = requireBatch(batchId);
        if (!"DRAFT".equals(batch.getMode())) {
            throw new NcgException(NcgErrorCode.SYS_409_001, "not a draft batch");
        }
        List<BatchItem> all = items.findByBatchId(batchId);
        int submitted = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (BatchItem item : all) {
            if (retryOnly && !"FAILED".equals(item.getStatus())) {
                continue; // 成功子项永不重发
            }
            if (!retryOnly && !"PENDING".equals(item.getStatus()) && !"FAILED".equals(item.getStatus())) {
                continue;
            }
            ChangeRequest child = changes.findById(item.getChangeNo()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getItemId());
            row.put("changeNo", item.getChangeNo());
            try {
                if (child == null) {
                    throw new NcgException(NcgErrorCode.SYS_404_001, "child change not found");
                }
                drafts.submit(child.getChangeNo(), operatorId, null, traceId);
                item.setStatus("SUCCEEDED");
                item.setErrorCode(null);
                item.setFinishedAt(Instant.now());
                submitted++;
                row.put("status", "SUCCEEDED");
            } catch (NcgException e) {
                item.setStatus("FAILED");
                item.setErrorCode(e.errorCode().code());
                item.setFinishedAt(Instant.now());
                failed++;
                row.put("status", "FAILED");
                row.put("errorCode", e.errorCode().code());
            }
            items.save(item);
            results.add(row);
        }
        if (submitted == 0 && failed == 0) {
            throw new NcgException(NcgErrorCode.SYS_409_001, "batch already submitted");
        }
        int succeededTotal = (int) all.stream().filter(i -> "SUCCEEDED".equals(i.getStatus())).count();
        int failedTotal = (int) all.stream().filter(i -> "FAILED".equals(i.getStatus())).count();
        batch.setSucceededCount(succeededTotal);
        batch.setFailedCount(failedTotal);
        batch.setStatus(failedTotal == 0 ? "SUBMITTED" : "PARTIAL_SUCCESS");
        batch.setUpdatedAt(Instant.now());
        batches.save(batch);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("status", batch.getStatus());
        out.put("submitted", submitted);
        out.put("retried", retryOnly ? submitted : 0);
        out.put("failed", failed);
        out.put("items", results);
        return out;
    }

    /** 创建响应补充 items[]（差异6 决策：契约 BatchCreateResponse 加法扩展，子项 itemId 从这里取）。 */
    public List<Map<String, Object>> itemRows(String batchId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BatchItem item : items.findByBatchId(batchId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getItemId());
            row.put("changeNo", item.getChangeNo());
            row.put("status", item.getStatus());
            changes.findById(item.getChangeNo()).ifPresent(cr -> {
                ConfigMapping mapping = catalog.getMapping(cr.getMappingId());
                row.put("mappingId", cr.getMappingId());
                row.put("dataId", mapping.getDataId());
                row.put("version", cr.getVersion());
                String format = mapping.getFormat() != null ? mapping.getFormat()
                        : ConfigContentCodec.formatOfDataId(mapping.getDataId());
                String content = drafts.contentOf(cr);
                int n = 0;
                if (content != null && !content.isBlank()) {
                    if ("TEXT".equals(format)) {
                        n = 1;
                    } else {
                        var parsed = ConfigContentCodec.parse(format, content);
                        if (parsed.parseError() == null) {
                            n = parsed.items().size();
                        }
                    }
                }
                row.put("itemCount", n);
            });
            out.add(row);
        }
        return out;
    }

    private Batch requireBatch(String batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "batch not found: " + batchId));
    }
}

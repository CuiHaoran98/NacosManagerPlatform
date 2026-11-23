package com.nacos.ncg.batch.app;

import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.batch.domain.Batch;
import com.nacos.ncg.batch.domain.BatchItem;
import com.nacos.ncg.batch.repo.BatchItemRepository;
import com.nacos.ncg.batch.repo.BatchRepository;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.notification.app.NotificationOutbox;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 批量执行（FR-12 / 流程 7.10 / R-SCH-04/06）：
 * 父单+子项；Worker 异步逐项执行，每项独立事务与发布记录；
 * 部分成功 PARTIAL_SUCCESS，只重试失败子项，成功子项永不重发。
 */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final BatchItemRepository items;
    private final ChangeRequestRepository changes;
    private final AuditWriter auditWriter;
    private final NotificationOutbox outbox;
    private final TransactionTemplate tx;
    private final int maxItems;

    public BatchService(BatchRepository batches, BatchItemRepository items,
                        ChangeRequestRepository changes, AuditWriter auditWriter,
                        NotificationOutbox outbox, PlatformTransactionManager txm,
                        @Value("${ncg.batch-max-items:50}") int maxItems) {
        this.batches = batches;
        this.items = items;
        this.changes = changes;
        this.auditWriter = auditWriter;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txm);
        this.maxItems = maxItems;
    }

    /** 批量父单查询（t6 联调：web 层按 mode 分流用；不存在返回 null）。 */
    public Batch getBatch(String batchId) {
        return batches.findById(batchId).orElse(null);
    }

    /** 创建批量（父单+子项）：子项变更单须均已 APPROVED。 */
    public Batch createBatch(List<String> changeNos, String operatorId, String traceId) {
        if (changeNos == null || changeNos.isEmpty()) {
            throw new NcgException(NcgErrorCode.CR_400_001, "changeNos must not be empty");
        }
        if (changeNos.size() > maxItems) {
            throw new NcgException(NcgErrorCode.BAT_413_001,
                    "batch size " + changeNos.size() + " exceeds limit " + maxItems + " (A-03)");
        }
        for (String changeNo : changeNos) {
            ChangeRequest cr = changes.findById(changeNo)
                    .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
            if (!"APPROVED".equals(cr.getStatus())) {
                throw new NcgException(NcgErrorCode.SCH_409_002,
                        "change " + changeNo + " must be APPROVED, current=" + cr.getStatus());
            }
        }
        Batch batch = new Batch();
        batch.setBatchId(Ulid.generate());
        // 父单号=批次伞号（设计 ch5 uk_batch_change：一 batch 一父单；子项才是真实变更单）
        batch.setParentChangeNo("CR-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                + "-B" + batch.getBatchId().substring(0, 8));
        batch.setStatus("CREATED");
        batch.setTotalCount(changeNos.size());
        batch.setSucceededCount(0);
        batch.setFailedCount(0);
        batch.setCreatedBy(operatorId);
        batch.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
        batch.setCreatedAt(Instant.now());
        batch.setUpdatedAt(Instant.now());
        // 状态与审计同事务（C-ARC-06）：批次+子项+审计原子提交
        Batch saved = tx.execute(s -> {
            Batch b = batches.save(batch);
            for (String changeNo : changeNos) {
                BatchItem item = new BatchItem();
                item.setItemId(Ulid.generate());
                item.setBatchId(b.getBatchId());
                item.setChangeNo(changeNo);
                item.setStatus("PENDING");
                items.save(item);
            }
            auditWriter.append(AuditWriter.Command.user(operatorId, null, AuditAction.BATCH_CREATE,
                    "BATCH", b.getBatchId(), traceId));
            return b;
        });
        return saved;
    }

    /**
     * 执行/续跑一批（Worker 调用）：逐项调 releaseAction（应为 ReleaseService.release）；
     * 成功子项跳过（R-SCH-06），失败子项单独记录错误码。
     */
    public void runBatch(String batchId, String systemActorId,
                         java.util.function.BiFunction<String, String, String> releaseAction) {
        Batch batch = batches.findById(batchId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "batch not found: " + batchId));
        batch.setStatus("RUNNING");
        batch.setUpdatedAt(Instant.now());
        batches.save(batch);
        int ok = 0;
        int failed = 0;
        for (BatchItem item : items.findByBatchId(batchId)) {
            if ("SUCCEEDED".equals(item.getStatus())) {
                ok++;
                continue; // 成功子项永不重发
            }
            try {
                String releaseId = releaseAction.apply(item.getChangeNo(), systemActorId);
                item.setStatus("SUCCEEDED");
                item.setReleaseId(releaseId);
                item.setErrorCode(null);
                ok++;
            } catch (NcgException ex) {
                item.setStatus("FAILED");
                item.setErrorCode(ex.errorCode().code());
                failed++;
            }
            item.setFinishedAt(Instant.now());
            items.save(item);
        }
        batch.setSucceededCount(ok);
        batch.setFailedCount(failed);
        batch.setStatus(failed == 0 ? "SUCCEEDED" : ok == 0 ? "FAILED" : "PARTIAL_SUCCESS");
        batch.setUpdatedAt(Instant.now());
        batches.save(batch);
        if (failed > 0) {
            final int okFinal = ok;
            final int failedFinal = failed;
            tx.executeWithoutResult(s -> {
                auditWriter.append(AuditWriter.Command.system(AuditAction.BATCH_ITEM_RETRY,
                        "BATCH", batch.getBatchId(), batch.getTraceId()));
                outbox.enqueue(NotificationOutbox.EventType.BATCH_PARTIAL_FAILED,
                        "BATCH", batch.getBatchId(),
                        Map.of("succeeded", okFinal, "failed", failedFinal),
                        "IN_APP");
            });
        }
    }

    /** 只重试失败子项（流程 7.10）。 */
    public void retryFailed(String batchId, String systemActorId,
                            java.util.function.BiFunction<String, String, String> releaseAction) {
        runBatch(batchId, systemActorId, releaseAction);
    }
}

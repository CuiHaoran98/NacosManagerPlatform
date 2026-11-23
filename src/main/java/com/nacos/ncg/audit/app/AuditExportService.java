package com.nacos.ncg.audit.app;

import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.audit.domain.AuditEvent;
import com.nacos.ncg.audit.repo.AuditEventRepository;
import com.nacos.ncg.shared.ContentDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * 审计导出（FR-05 / §10.4）：CSV 流式导出，上限 10 万条 [TBD]，
 * 尾附条数与内容 SHA-256（导出内容自身可校验）；导出动作本身写 AUDIT_EXPORT 审计。
 */
@Service
public class AuditExportService {

    public static final int EXPORT_LIMIT = 100_000;

    private final AuditEventRepository events;
    private final AuditWriter auditWriter;
    private final TransactionTemplate tx;

    public AuditExportService(AuditEventRepository events, AuditWriter auditWriter,
                              PlatformTransactionManager transactionManager) {
        this.events = events;
        this.auditWriter = auditWriter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 返回完整 CSV（含表头与尾注）。超过上限截断并在尾注标记。 */
    public String exportCsv(LocalDate chainDate, String operatorId, String traceId) {
        List<AuditEvent> rows = events.findByChainDateOrderBySeqAsc(chainDate);
        StringBuilder sb = new StringBuilder();
        sb.append("seq,occurred_at,actor_type,actor_id,action,object_type,object_id,result,error_code,trace_id,prev_hash,event_hash\n");
        int count = 0;
        boolean truncated = false;
        for (AuditEvent e : rows) {
            if (count >= EXPORT_LIMIT) {
                truncated = true;
                break;
            }
            sb.append(csv(e.getSeq())).append(',')
                    .append(csv(String.valueOf(e.getOccurredAt()))).append(',')
                    .append(csv(e.getActorType())).append(',')
                    .append(csv(e.getActorId())).append(',')
                    .append(csv(e.getAction())).append(',')
                    .append(csv(e.getObjectType())).append(',')
                    .append(csv(e.getObjectId())).append(',')
                    .append(csv(e.getResult())).append(',')
                    .append(csv(e.getErrorCode())).append(',')
                    .append(csv(e.getTraceId())).append(',')
                    .append(csv(e.getPrevHash())).append(',')
                    .append(csv(e.getEventHash())).append('\n');
            count++;
        }
        String body = sb.toString();
        sb.append("# count=").append(count)
                .append(" truncated=").append(truncated)
                .append(" sha256=").append(ContentDigest.sha256Hex(body)).append('\n');
        tx.executeWithoutResult(s -> auditWriter.append(AuditWriter.Command.user(operatorId, null,
                AuditAction.AUDIT_EXPORT, "audit_chain", chainDate.toString(), traceId)));
        return sb.toString();
    }

    private static String csv(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

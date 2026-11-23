package com.nacos.ncg.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.audit.app.AuditExportService;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.audit.domain.AuditEvent;
import com.nacos.ncg.audit.repo.AuditEventRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 审计导出 REST（FR-05 / §10.4）：CSV/JSON 导出（上限 10 万条 [TBD]），
 * CSV 尾附条数与 SHA-256；导出动作本身写 AUDIT_EXPORT 审计。
 * 契约 #42：POST /audit-events/export body {format:'CSV'|'JSON',keyword,eventType,actor,from,to,page,size}。
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditExportController {

    private final AuditExportService exportService;
    private final AuditEventRepository events;
    private final AuditWriter auditWriter;

    public AuditExportController(AuditExportService exportService, AuditEventRepository events,
                                 AuditWriter auditWriter) {
        this.exportService = exportService;
        this.events = events;
        this.auditWriter = auditWriter;
    }

    /** 导出请求体（契约 AuditExportRequest；字段全部可选，date 兼容旧口径）。 */
    public record ExportRequest(String format, String date, String from, String to,
                                String keyword, String eventType, String actor,
                                Integer page, Integer size) {}

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String date,
                                         Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        LocalDate chainDate = date == null || date.isBlank()
                ? LocalDate.now(ZoneId.of("UTC"))
                : LocalDate.parse(date);
        String csv = exportService.exportCsv(chainDate, actorId, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ncg-audit-" + chainDate + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 规格方法：POST /audit-events/export（§6.2）。body 可空（等价 GET）；format=JSON 返 JSON 数组。 */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportPost(@RequestBody(required = false) ExportRequest req,
                                             @RequestParam(required = false) String date,
                                             Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        String format = req == null || req.format() == null || req.format().isBlank()
                ? "CSV" : req.format().toUpperCase();
        if (!"CSV".equals(format) && !"JSON".equals(format)) {
            throw new com.nacos.ncg.shared.NcgException(com.nacos.ncg.shared.NcgErrorCode.CR_400_001,
                    "format must be CSV or JSON");
        }
        // 兼容：无过滤条件且给了 date → 走既有按日 CSV 导出服务（含尾附哈希）
        boolean noFilter = (req == null || (isBlank(req.keyword()) && isBlank(req.eventType())
                && isBlank(req.actor()) && isBlank(req.from()) && isBlank(req.to())));
        String dateParam = req != null && !isBlank(req.date()) ? req.date() : date;
        if ("CSV".equals(format) && noFilter) {
            return export(dateParam, principal);
        }
        List<AuditEvent> all = new ArrayList<>();
        events.findAll().forEach(all::add);
        all.sort(Comparator.comparing(AuditEvent::getOccurredAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AuditEvent::getSeq, Comparator.nullsLast(Comparator.reverseOrder())));
        List<AuditEvent> filtered = AuditController.filterAudit(all,
                req == null ? null : req.keyword(), req == null ? null : req.eventType(),
                req == null ? null : req.actor(), req == null ? null : req.from(),
                req == null ? null : req.to());
        byte[] body;
        String filename;
        MediaType mediaType;
        if ("JSON".equals(format)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AuditEvent e : filtered) {
                rows.add(ContractViews.auditRow(e));
            }
            try {
                body = new ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writerWithDefaultPrettyPrinter().writeValueAsBytes(rows);
            } catch (Exception ex) {
                throw new com.nacos.ncg.shared.NcgException(
                        com.nacos.ncg.shared.NcgErrorCode.SYS_500_001, "export json failed", null, ex);
            }
            filename = "ncg-audit-export.json";
            mediaType = MediaType.APPLICATION_JSON;
        } else {
            StringBuilder sb = new StringBuilder(
                    "eventId,occurredAt,actorType,actorId,action,objectType,objectId,result,errorCode,changeNo,traceId,prevHash,eventHash\n");
            for (AuditEvent e : filtered) {
                sb.append(csv(e.getEventId())).append(',').append(csv(String.valueOf(e.getOccurredAt())))
                        .append(',').append(csv(e.getActorType())).append(',').append(csv(e.getActorId()))
                        .append(',').append(csv(e.getAction())).append(',').append(csv(e.getObjectType()))
                        .append(',').append(csv(e.getObjectId())).append(',').append(csv(e.getResult()))
                        .append(',').append(csv(e.getErrorCode())).append(',').append(csv(e.getChangeNo()))
                        .append(',').append(csv(e.getTraceId())).append(',').append(csv(e.getPrevHash()))
                        .append(',').append(csv(e.getEventHash())).append('\n');
            }
            sb.append("# rows: ").append(filtered.size()).append('\n');
            body = sb.toString().getBytes(StandardCharsets.UTF_8);
            filename = "ncg-audit-export.csv";
            mediaType = new MediaType("text", "csv", StandardCharsets.UTF_8);
        }
        auditWriter.append(AuditWriter.Command.user(actorId, null, AuditAction.AUDIT_EXPORT,
                "AUDIT", filename, "format=" + format + ",rows=" + filtered.size()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(body);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String csv(String v) {
        if (v == null) return "";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}

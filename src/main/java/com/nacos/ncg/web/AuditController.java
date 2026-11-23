package com.nacos.ncg.web;

import com.nacos.ncg.audit.app.AuditChainService;
import com.nacos.ncg.audit.domain.AuditEvent;
import com.nacos.ncg.audit.repo.AuditEventRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计查询与链校验（FR-05 / §6.5）：审计只追加可导出校验；
 * 敏感值一律脱敏后可见（明文走独立 sensitive-view 接口）。
 * 契约 #41：GET /audit-events 支持 keyword/eventType/actor/from/to/page/size
 * （changeNo 保留为兼容参数），返回 PageResult 形状 {items,total,page,size}。
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    private final AuditEventRepository events;
    private final AuditChainService chain;

    public AuditController(AuditEventRepository events, AuditChainService chain) {
        this.events = events;
        this.chain = chain;
    }

    @GetMapping
    public Map<String, Object> list(Principal principal,
                                    @RequestParam(required = false) String changeNo,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String eventType,
                                    @RequestParam(required = false) String actor,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        ActorResolver.requireActor(principal);
        List<AuditEvent> all = new ArrayList<>();
        if (changeNo == null || changeNo.isBlank()) {
            events.findAll().forEach(all::add);
        } else {
            all.addAll(events.findByChangeNoOrderBySeqDesc(changeNo));
        }
        all.sort(Comparator.comparing(AuditEvent::getOccurredAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AuditEvent::getSeq, Comparator.nullsLast(Comparator.reverseOrder())));
        List<AuditEvent> filtered = filterAudit(all, keyword, eventType, actor, from, to);
        return pageOf(filtered, page, size);
    }

    /** 过滤：keyword 模糊（action/objectId/changeNo/traceId）、eventType=action、actor=actorId、from/to=occurredAt。 */
    static List<AuditEvent> filterAudit(List<AuditEvent> all, String keyword, String eventType,
                                        String actor, String from, String to) {
        java.time.Instant fromT = parseInstant(from);
        java.time.Instant toT = parseInstant(to);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.toLowerCase();
        List<AuditEvent> out = new ArrayList<>();
        for (AuditEvent e : all) {
            if (eventType != null && !eventType.isBlank() && !eventType.equals(e.getAction())) continue;
            if (actor != null && !actor.isBlank() && !actor.equals(e.getActorId())) continue;
            if (fromT != null && (e.getOccurredAt() == null || e.getOccurredAt().isBefore(fromT))) continue;
            if (toT != null && (e.getOccurredAt() == null || e.getOccurredAt().isAfter(toT))) continue;
            if (kw != null) {
                String hay = (nvl(e.getAction()) + " " + nvl(e.getObjectId()) + " "
                        + nvl(e.getChangeNo()) + " " + nvl(e.getTraceId()) + " " + nvl(e.getActorId()))
                        .toLowerCase();
                if (!hay.contains(kw)) continue;
            }
            out.add(e);
        }
        return out;
    }

    static Map<String, Object> pageOf(List<AuditEvent> filtered, Integer page, Integer size) {
        int pg = page == null || page < 0 ? 0 : page;
        int sz = size == null || size <= 0 ? 20 : Math.min(size, 100);
        int from = pg * sz;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < Math.min(from + sz, filtered.size()); i++) {
            items.add(ContractViews.auditRow(filtered.get(i)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", filtered.size());
        out.put("page", pg);
        out.put("size", sz);
        return out;
    }

    static java.time.Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return java.time.Instant.parse(v);
        } catch (Exception e) {
            try {
                return java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (Exception e2) {
                throw new NcgException(NcgErrorCode.CR_400_001,
                        "invalid time: " + v + " (expect ISO-8601 instant or YYYY-MM-DD)");
            }
        }
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    /** 按日哈希链校验（R-AUD-05）：断链只报告不修复（§13）。 */
    @GetMapping("/chain")
    public AuditChainService.Report verifyChain(Principal principal, @RequestParam String date) {
        ActorResolver.requireActor(principal);
        LocalDate d;
        try {
            d = LocalDate.parse(date);
        } catch (Exception e) {
            throw new NcgException(NcgErrorCode.CR_400_001, "date must be YYYY-MM-DD");
        }
        return chain.verify(d);
    }
}

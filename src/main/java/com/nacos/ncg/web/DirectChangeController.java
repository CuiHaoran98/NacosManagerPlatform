package com.nacos.ncg.web;

import com.nacos.ncg.audit.domain.DirectChangeEvent;
import com.nacos.ncg.audit.repo.DirectChangeEventRepository;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 直连修改事件查询（FR-05）：检测结果只读展示。
 * 契约 #43/#44：行形状 {id,instanceId,dataId,detectedAt,matched,status}（原字段保留）；
 * 支持 status=OPEN|CLOSED（按 closedAt）与 verdict 兼容过滤。
 */
@RestController
@RequestMapping("/api/v1/direct-changes")
public class DirectChangeController {

    private final DirectChangeEventRepository events;
    private final com.nacos.ncg.audit.app.AuditWriter auditWriter;

    public DirectChangeController(DirectChangeEventRepository events,
                                  com.nacos.ncg.audit.app.AuditWriter auditWriter) {
        this.events = events;
        this.auditWriter = auditWriter;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String verdict,
                                          @RequestParam(required = false) String status) {
        List<DirectChangeEvent> all = new ArrayList<>(
                verdict == null || verdict.isBlank()
                        ? (List<DirectChangeEvent>) events.findAll()
                        : events.findByVerdict(verdict));
        all.sort(Comparator.comparing(DirectChangeEvent::getDetectedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DirectChangeEvent e : all) {
            boolean open = e.getClosedAt() == null;
            if ("OPEN".equalsIgnoreCase(status) && !open) continue;
            if ("CLOSED".equalsIgnoreCase(status) && open) continue;
            rows.add(ContractViews.directChangeRow(e));
        }
        return rows;
    }

    /** 关闭直连事件（§6.2 POST /direct-changes/{id}/close；DIRECT_CHANGE_CLOSED 审计）。 */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/{eventId}/close")
    public Map<String, Object> close(@PathVariable String eventId, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        DirectChangeEvent event = events.findById(eventId)
                .orElseThrow(() -> new com.nacos.ncg.shared.NcgException(
                        com.nacos.ncg.shared.NcgErrorCode.SYS_404_001, "direct change event not found: " + eventId));
        event.setClosedAt(java.time.Instant.now());
        events.save(event);
        auditWriter.append(com.nacos.ncg.audit.app.AuditWriter.Command.user(actorId, null,
                com.nacos.ncg.audit.domain.AuditAction.DIRECT_CHANGE_CLOSED,
                "direct_change_event", eventId, null));
        return ContractViews.directChangeRow(event);
    }

}

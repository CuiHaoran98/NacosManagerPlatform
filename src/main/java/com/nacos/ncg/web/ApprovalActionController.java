package com.nacos.ncg.web;

import com.nacos.ncg.approval.app.ApprovalService;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.shared.content.ConfigContentCodec;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批动作的规格路径（契约 #19-21 POST /changes/{changeNo}/approve|reject|transfer）。
 * 与 /changes/{changeNo}/approval 单入口同实现（ApprovalService.decide），
 * 分立路径对应 §6.6 错误码入口映射（AUTHZ-403-002/003、APR-409-001、APR-422-001）。
 * 响应=ChangeDetail 主要字段（version/dataId/format/plannedAt/createdBy 等，items/content 为可选字段不回传）。
 */
@RestController
@RequestMapping("/api/v1/changes")
public class ApprovalActionController {

    private final ApprovalService approvals;
    private final CatalogService catalog;

    public ApprovalActionController(ApprovalService approvals, CatalogService catalog) {
        this.approvals = approvals;
        this.catalog = catalog;
    }

    /** reason=驳回/转交原因；targetUserId/targetApproverId 双别名（契约 TransferRequest{targetUserId}）。 */
    public record ActionRequest(String reason, String targetApproverId, String targetUserId) {
        String target() {
            return targetApproverId != null && !targetApproverId.isBlank() ? targetApproverId : targetUserId;
        }
    }

    @PostMapping("/{changeNo}/approve")
    public Map<String, Object> approve(@PathVariable String changeNo, @RequestBody ActionRequest req,
                              Principal principal) {
        return decide(changeNo, "APPROVE", req, principal);
    }

    @PostMapping("/{changeNo}/reject")
    public Map<String, Object> reject(@PathVariable String changeNo, @RequestBody ActionRequest req,
                             Principal principal) {
        return decide(changeNo, "REJECT", req, principal);
    }

    @PostMapping("/{changeNo}/transfer")
    public Map<String, Object> transfer(@PathVariable String changeNo, @RequestBody ActionRequest req,
                             Principal principal) {
        return decide(changeNo, "TRANSFER", req, principal);
    }

    private Map<String, Object> decide(String changeNo, String action, ActionRequest req, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        ChangeRequest cr = approvals.decide(changeNo, action, req.reason(), req.target(),
                actorId, null);
        return detail(cr);
    }

    /** ChangeDetail 主字段（与 ChangeController.detail 的 items/content 丰富字段互补；这里不含可选明细）。 */
    private Map<String, Object> detail(ChangeRequest cr) {
        Map<String, Object> row = ContractViews.changeViewRow(ChangeView.of(cr));
        row.put("version", cr.getVersion());
        row.put("createdBy", cr.getSubmitterId());
        row.put("plannedAt", cr.getScheduleAt());
        row.put("dataId", safeDataId(cr.getMappingId()));
        row.put("format", safeFormat(cr.getMappingId()));
        return row;
    }

    private String safeDataId(String mappingId) {
        try {
            return catalog.getMapping(mappingId).getDataId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeFormat(String mappingId) {
        try {
            ConfigMapping m = catalog.getMapping(mappingId);
            return m.getFormat() != null ? m.getFormat() : ConfigContentCodec.formatOfDataId(m.getDataId());
        } catch (RuntimeException e) {
            return null;
        }
    }
}

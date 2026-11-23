package com.nacos.ncg.web;

import com.nacos.ncg.approval.app.ApprovalService;
import com.nacos.ncg.change.domain.ChangeRequest;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * 审批决策（FR-03 / 流程 7.2，§6.2）：APPROVE / REJECT / TRANSFER。
 * 职责分离与高风险负责人校验在服务内（fail-closed）。
 */
@RestController
@RequestMapping("/api/v1/changes/{changeNo}/approval")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping
    public ChangeView decide(Principal principal, @PathVariable String changeNo,
                             @RequestBody DecisionRequest req) {
        String actor = ActorResolver.requireActor(principal);
        ChangeRequest cr = approvals.decide(changeNo, req.action(), req.reason(),
                req.targetApproverId(), actor, null);
        return ChangeView.of(cr);
    }

    public record DecisionRequest(String action, String reason, String targetApproverId) { }

}

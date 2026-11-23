package com.nacos.ncg.approval.app;

import com.nacos.ncg.approval.domain.Approval;
import com.nacos.ncg.approval.repo.ApprovalRepository;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.domain.ChangeRequestState;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.notification.app.NotificationOutbox;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.PermissionPolicy;
import com.nacos.ncg.shared.Ulid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 审批服务（FR-03 / 流程 7.2 / R-APR-01~05）：
 * 仅 PENDING_APPROVAL 可决策（APR-409-001）；职责分离 fail-closed（AUTHZ-403-002/003）；
 * 驳回必填理由 ≥10 字（APR-422-001）并回 DRAFT；通过 → APPROVED；
 * 决策、审批记录、审计、通知入队同事务。
 */
@Service
public class ApprovalService {

    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT", "TRANSFER");

    private final ChangeRequestRepository changes;
    private final ApprovalRepository approvals;
    private final CatalogService catalog;
    private final AuditWriter auditWriter;
    private final NotificationOutbox outbox;
    private final PermissionPolicy policy = new PermissionPolicy();

    public ApprovalService(ChangeRequestRepository changes, ApprovalRepository approvals,
                           CatalogService catalog, AuditWriter auditWriter, NotificationOutbox outbox) {
        this.changes = changes;
        this.approvals = approvals;
        this.catalog = catalog;
        this.auditWriter = auditWriter;
        this.outbox = outbox;
    }

    /**
     * 决策（APPROVE / REJECT / TRANSFER）。生产单人会签 1 名审批人即通过，无审批超时（PRD 决策 3/14）。
     * 高风险仅应用负责人（或审计过的代理人 [TBD A-06]）；提交人/发布人不得审批自己发起的单。
     */
    @Transactional
    public ChangeRequest decide(String changeNo, String action, String reason,
                                String targetApproverId, String actorId, String traceId) {
        if (!DECISIONS.contains(action)) {
            throw new NcgException(NcgErrorCode.CR_400_001, "unknown approval action: " + action);
        }
        ChangeRequest cr = changes.findById(changeNo)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "change not found: " + changeNo));
        if (!ChangeRequestState.PENDING_APPROVAL.name().equals(cr.getStatus())) {
            throw new NcgException(NcgErrorCode.APR_409_001,
                    "change not pending approval: " + cr.getStatus());
        }
        App app = catalog.getApp(cr.getAppId());
        boolean highRisk = "HIGH".equals(cr.getRiskLevel());

        if ("TRANSFER".equals(action)) {
            if (targetApproverId == null || targetApproverId.isBlank()) {
                throw new NcgException(NcgErrorCode.CR_400_001, "targetApproverId is required for TRANSFER");
            }
        } else {
            PermissionPolicy.Decision decision = policy.canApprove(
                    actorId, cr.getSubmitterId(), cr.getPublisherId(),
                    highRisk, app.getOwnerId(), false /* 代理人 A-06 TBD，P0 不放行 */);
            switch (decision) {
                case DENY_SAME_ACTOR, DENY_SEPARATION ->
                        throw new NcgException(NcgErrorCode.AUTHZ_403_002,
                                "separation of duties violated (R-APR-02/03)");
                case DENY_NOT_OWNER_FOR_HIGH ->
                        throw new NcgException(NcgErrorCode.AUTHZ_403_003,
                                "high-risk change requires app owner approval (R-APR-04)");
                default -> { /* ALLOW */ }
            }
        }

        Approval record = new Approval();
        record.setApprovalId(Ulid.generate());
        record.setChangeNo(changeNo);
        record.setActorId(actorId);
        record.setAction(action);
        record.setReason(reason);
        record.setTargetApproverId(targetApproverId);
        record.setTraceId(com.nacos.ncg.shared.TraceIds.orNew(traceId));
        record.setCreatedAt(Instant.now());
        approvals.save(record);

        AuditAction auditAction = switch (action) {
            case "APPROVE" -> AuditAction.APPROVAL_APPROVE;
            case "REJECT" -> AuditAction.APPROVAL_REJECT;
            default -> AuditAction.APPROVAL_TRANSFER;
        };

        switch (action) {
            case "APPROVE" -> {
                cr.setStatus(ChangeRequestState.APPROVED.name());
                outbox.enqueue(NotificationOutbox.EventType.CHANGE_APPROVED, "CHANGE", changeNo,
                        Map.of("changeNo", changeNo, "operator", actorId), null);
            }
            case "REJECT" -> {
                if (reason == null || reason.trim().length() < 10) {
                    throw new NcgException(NcgErrorCode.APR_422_001,
                            "reject requires a reason of at least 10 characters");
                }
                cr.setStatus(ChangeRequestState.DRAFT.name()); // 驳回回 DRAFT（流程 7.2）
                outbox.enqueue(NotificationOutbox.EventType.CHANGE_REJECTED, "CHANGE", changeNo,
                        Map.of("changeNo", changeNo, "operator", actorId), null);
            }
            case "TRANSFER" -> {
                // 转交：状态保持 PENDING_APPROVAL，目标审批人进入待办（P0 记录即可）
                cr.setUpdatedAt(Instant.now());
            }
            default -> { }
        }
        cr.setUpdatedAt(Instant.now());
        ChangeRequest saved = changes.save(cr);

        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, actorId, null, auditAction,
                "CHANGE", changeNo, null, null, true, null, changeNo, traceId));
        return saved;
    }
}

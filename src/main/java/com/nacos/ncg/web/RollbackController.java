package com.nacos.ncg.web;

import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.release.app.RollbackService;
import com.nacos.ncg.release.app.RollbackService.RollbackResult;
import com.nacos.ncg.release.domain.ReleaseRecord;
import com.nacos.ncg.release.repo.ReleaseRecordRepository;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回滚 REST（FR-08 / 流程 7.6）：POST /api/v1/changes/{changeNo}/rollback。
 * 目标=上一版本直接执行（发布人≠审批人，原因≥10 字）；更早版本返回 mode=DRAFT_REQUIRED 的回滚单。
 * 契约 #23 兼容：body {targetVersion}（前端口径）或 {targetReleaseId}（后端原字段）；
 * reason 可选（缺省补默认原因）。响应补 ReleaseDetail 形状（result/readbackMd5/releasedBy/releasedAt）。
 */
@RestController
@RequestMapping("/api/v1/changes/{changeNo}")
public class RollbackController {

    private final RollbackService rollbackService;
    private final ReleaseRecordRepository records;

    public RollbackController(RollbackService rollbackService, ReleaseRecordRepository records) {
        this.rollbackService = rollbackService;
        this.records = records;
    }

    public record RollbackRequest(String targetReleaseId, String targetVersion, String reason) {}

    @PostMapping("/rollback")
    public Map<String, Object> rollback(@PathVariable String changeNo,
                                        @RequestBody RollbackRequest request,
                                        Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        String targetReleaseId = request == null ? null : request.targetReleaseId();
        String targetVersion = request == null ? null : request.targetVersion();
        if ((targetReleaseId == null || targetReleaseId.isBlank())
                && (targetVersion == null || targetVersion.isBlank())) {
            throw new com.nacos.ncg.shared.NcgException(
                    com.nacos.ncg.shared.NcgErrorCode.CR_400_001, "targetVersion (or targetReleaseId) is required");
        }
        if (targetReleaseId == null || targetReleaseId.isBlank()) {
            // 契约口径：按 变更单号:目标版本 幂等键反查发布记录
            ReleaseRecord byKey = records.findByReleaseKey(changeNo + ":" + targetVersion).orElse(null);
            if (byKey == null) {
                throw new com.nacos.ncg.shared.NcgException(
                        com.nacos.ncg.shared.NcgErrorCode.SYS_404_001,
                        "release not found for " + changeNo + ":" + targetVersion);
            }
            targetReleaseId = byKey.getReleaseId();
        }
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "rollback to " + targetVersion : request.reason();
        RollbackResult result = rollbackService.rollback(changeNo, targetReleaseId,
                reason, actorId, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", result.mode());
        if (result.record() != null) {
            ReleaseRecord r = result.record();
            body.putAll(ContractViews.releaseRow(r));
        } else {
            ChangeRequest cr = result.change();
            body.put("changeNo", cr.getChangeNo());
            body.put("status", cr.getStatus());
            body.put("result", "draft_required");
        }
        return body;
    }
}

package com.nacos.ncg.web;

import com.nacos.ncg.catalog.app.CatalogService;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.release.app.ReleaseService;
import com.nacos.ncg.release.domain.ReleaseRecord;
import com.nacos.ncg.release.repo.ReleaseRecordRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布编排入口（FR-04 / 流程 7.3~7.4，§6.2）：
 * POST release（幂等键=变更单号:目标版本，REL-409-002 幂等命中返既有记录）；
 * POST reconcile（INDETERMINATE 人工对账，确认生效/未生效，只追加审计）。
 * 契约对齐：release 响应补 ReleaseDetail 形状（releaseId/result/readbackMd5/releasedBy/releasedAt/…）；
 * GET /releases 的 changeNo 改为可选（差异3 决策=契约口径，缺省返近期全量）；
 * reconcile body 支持 conclusion 三选一 EFFECTIVE/NOT_EFFECTIVE/OVERRIDDEN（差异5，OVERRIDDEN 记审计）。
 */
@RestController
@RequestMapping("/api/v1")
public class ReleaseController {

    private final ReleaseService releases;
    private final ReleaseRecordRepository records;
    private final ChangeRequestRepository changes;
    private final CatalogService catalog;
    private final com.nacos.ncg.audit.app.AuditWriter auditWriter;

    public ReleaseController(ReleaseService releases, ReleaseRecordRepository records,
                             ChangeRequestRepository changes, CatalogService catalog,
                             com.nacos.ncg.audit.app.AuditWriter auditWriter) {
        this.releases = releases;
        this.records = records;
        this.changes = changes;
        this.catalog = catalog;
        this.auditWriter = auditWriter;
    }

    @PostMapping("/changes/{changeNo}/release")
    public Map<String, Object> release(Principal principal, @PathVariable String changeNo,
                                       @RequestBody(required = false) ReleaseRequest req) {
        String actor = ActorResolver.requireActor(principal);
        String targetVersion = req == null || req.targetVersion() == null || req.targetVersion().isBlank()
                ? "1" : req.targetVersion();
        ReleaseService.ReleaseResult result = releases.release(changeNo, targetVersion, actor,
                req == null || req.grayMethod() == null ? null
                        : new ReleaseService.GraySpec(req.grayMethod(), req.grayCondition(),
                                req.observationWindowMinutes()), null);
        // 契约 TC-20 / §2 #22：结果不确定 → 502 REL-502-001（记录已落库，绝不自动重试）
        if ("INDETERMINATE".equals(result.record().getOutcome())) {
            throw new NcgException(NcgErrorCode.REL_502_001,
                    "发布结果不确定，需人工对账",
                    java.util.Map.of("releaseId", String.valueOf(result.record().getReleaseId()),
                            "changeNo", changeNo, "outcome", "INDETERMINATE"));
        }
        return releaseDetailBody(result.record(), result.change(), null);
    }

    @PostMapping("/changes/{changeNo}/reconcile")
    public Map<String, Object> reconcile(Principal principal, @PathVariable String changeNo,
                                         @RequestBody(required = false) ReconcileRequest req) {
        String actor = ActorResolver.requireActor(principal);
        boolean confirmed = confirmedOf(req);
        ChangeRequest cr = releases.reconcile(changeNo, confirmed, actor, null);
        Map<String, Object> body = new LinkedHashMap<>(ContractViews.changeViewRow(ChangeView.of(cr)));
        body.put("confirmedEffective", confirmed);
        body.put("conclusion", conclusionOf(req, confirmed));
        return body;
    }

    /** 差异3：changeNo 可选；支持 result/envId/page/size 过滤（前端 ReleaseListQuery 口径）。 */
    @GetMapping("/releases")
    public List<Map<String, Object>> list(Principal principal,
                                          @RequestParam(required = false) String changeNo,
                                          @RequestParam(required = false) String result,
                                          @RequestParam(required = false) String envId,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        ActorResolver.requireActor(principal);
        List<ReleaseRecord> all = new ArrayList<>();
        if (changeNo == null || changeNo.isBlank()) {
            records.findAll().forEach(all::add);
        } else {
            all.addAll(records.findByChangeNoOrderByStartedAtDesc(changeNo));
        }
        all.sort(Comparator.comparing(ReleaseRecord::getStartedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReleaseRecord r : all) {
            if (result != null && !result.isBlank()
                    && !result.equalsIgnoreCase(r.getOutcome())) continue;
            if (envId != null && !envId.isBlank()) {
                ChangeRequest cr = changes.findById(r.getChangeNo()).orElse(null);
                if (cr == null || !envId.equals(cr.getEnvId())) continue;
            }
            rows.add(ContractViews.releaseRow(r));
        }
        if (page != null || size != null) {
            int pg = page == null || page < 0 ? 0 : page;
            int sz = size == null || size <= 0 ? 20 : Math.min(size, 100);
            int from = Math.min(pg * sz, rows.size());
            rows = new ArrayList<>(rows.subList(from, Math.min(from + sz, rows.size())));
        }
        return rows;
    }

    public record ReleaseRequest(String targetVersion, String grayMethod, String grayCondition,
                                  Integer observationWindowMinutes) { }

    /** confirmedEffective=旧字段；conclusion=契约三选一（EFFECTIVE/NOT_EFFECTIVE/OVERRIDDEN）。 */
    public record ReconcileRequest(Boolean confirmedEffective, String conclusion) { }

    /** 发布记录详情（§6.2 GET /releases/{releaseId}，ReleaseDetail 形状）。 */
    @GetMapping("/releases/{releaseId}")
    public Map<String, Object> releaseDetail(@PathVariable String releaseId, Principal principal) {
        ActorResolver.requireActor(principal);
        ReleaseRecord r = records.findById(releaseId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "release not found: " + releaseId));
        ChangeRequest cr = changes.findById(r.getChangeNo()).orElse(null);
        return releaseDetailBody(r, cr, null);
    }

    /** 按发布记录对账（§6.2 POST /releases/{releaseId}/reconcile，R-REL-06；差异5 conclusion 三选一）。 */
    @PostMapping("/releases/{releaseId}/reconcile")
    public Map<String, Object> reconcileByRelease(@PathVariable String releaseId,
                                                  @RequestBody(required = false) ReconcileRequest request,
                                                  Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        ReleaseRecord r = records.findById(releaseId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "release not found: " + releaseId));
        boolean confirmed = confirmedOf(request);
        String conclusion = conclusionOf(request, confirmed);
        releases.reconcile(r.getChangeNo(), confirmed, actorId, null);
        if ("OVERRIDDEN".equals(conclusion)) {
            // 决策：OVERRIDDEN = 人工判定现场已被其他写入覆盖；按生效处理并追加审计标注
            auditWriter.append(com.nacos.ncg.audit.app.AuditWriter.Command.user(actorId, null,
                    com.nacos.ncg.audit.domain.AuditAction.RELEASE_RECONCILE,
                    "release_record", releaseId, "conclusion=OVERRIDDEN"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.putAll(ContractViews.releaseRow(r));
        body.put("releaseId", releaseId);
        body.put("changeNo", r.getChangeNo());
        body.put("confirmedEffective", confirmed);
        body.put("conclusion", conclusion);
        return body;
    }

    private boolean confirmedOf(ReconcileRequest req) {
        if (req == null) {
            return false;
        }
        if (req.conclusion() != null && !req.conclusion().isBlank()) {
            String c = req.conclusion().toUpperCase(java.util.Locale.ROOT);
            return switch (c) {
                case "EFFECTIVE", "OVERRIDDEN" -> true;
                case "NOT_EFFECTIVE" -> false;
                default -> throw new NcgException(NcgErrorCode.CR_400_001,
                        "conclusion must be EFFECTIVE/NOT_EFFECTIVE/OVERRIDDEN");
            };
        }
        return Boolean.TRUE.equals(req.confirmedEffective());
    }

    private String conclusionOf(ReconcileRequest req, boolean confirmed) {
        if (req != null && req.conclusion() != null && !req.conclusion().isBlank()) {
            return req.conclusion().toUpperCase(java.util.Locale.ROOT);
        }
        return confirmed ? "EFFECTIVE" : "NOT_EFFECTIVE";
    }

    /** ReleaseDetail 形状：releaseRow + dataId/envId/traceId/error + preflight 占位（可选字段）。 */
    private Map<String, Object> releaseDetailBody(ReleaseRecord r, ChangeRequest cr, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (r != null) {
            body.putAll(ContractViews.releaseRow(r));
            body.put("error", r.getErrorCode());
            body.put("nacosReceipt", r.getNacosReceipt() == null ? null : r.getNacosReceipt().json());
        }
        if (cr != null) {
            body.putIfAbsent("changeNo", cr.getChangeNo());
            body.put("envId", cr.getEnvId());
            body.put("traceId", traceId != null ? traceId : cr.getTraceId());
            var mapping = cr.getMappingId() == null ? null : catalog.getMapping(cr.getMappingId());
            if (mapping != null) {
                body.put("dataId", mapping.getDataId());
                body.put("mappingId", mapping.getMappingId());
            }
            if (r == null) {
                body.put("changeNo", cr.getChangeNo());
                body.put("status", cr.getStatus());
            }
        }
        return body;
    }

    /** R-REL-03：灰度放量/转全量结果记录（发布记录终态不可改，结果只追加审计）。 */
    public record GrayPhaseRequest(String phase, String note) {}

    @PostMapping("/releases/{releaseId}/gray-phase")
    public Map<String, Object> grayPhase(@PathVariable String releaseId,
                                         @RequestBody GrayPhaseRequest req, Principal principal) {
        String actorId = ActorResolver.requireActor(principal);
        releases.recordGrayPhase(releaseId, req.phase(), req.note(), actorId, null);
        return Map.of("releaseId", releaseId,
                "phase", req.phase() == null ? "" : req.phase().toUpperCase(java.util.Locale.ROOT),
                "recorded", true);
    }
}

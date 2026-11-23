package com.nacos.ncg.envsync.app;

import org.springframework.beans.factory.annotation.Autowired;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.change.app.ChangeDraftService;
import com.nacos.ncg.change.domain.ChangeRequest;
import com.nacos.ncg.change.repo.ChangeRequestRepository;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.envsync.domain.EnvSyncSnapshot;
import com.nacos.ncg.envsync.repo.EnvSyncSnapshotRepository;
import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨环境同步（FR-11 / 流程 7.8 / R-SYN-01~05）：
 * 只读对比源/目标环境，只创建目标环境变更单（changeType=SYNC），绝不直写目标 Nacos（R-SYN-02）；
 * 阻断条件：目标映射缺失、目标存在活跃变更单、内容格式不一致（R-SYN-03/04）。
 */
@Service
public class EnvSyncService {

    @Autowired private com.nacos.ncg.catalog.app.InstanceService instanceService;
    @Autowired private com.nacos.ncg.catalog.app.CatalogService catalogService;

    private final NacosConfigPort port;
    private final ConfigMappingRepository mappings;
    private final ChangeRequestRepository changes;
    private final ChangeDraftService drafts;
    private final EnvSyncSnapshotRepository snapshots;
    private final AuditWriter auditWriter;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public EnvSyncService(NacosConfigPort port, ConfigMappingRepository mappings,
                          ChangeRequestRepository changes, ChangeDraftService drafts,
                          EnvSyncSnapshotRepository snapshots, AuditWriter auditWriter,
                          org.springframework.transaction.support.TransactionTemplate tx) {
        this.port = port;
        this.mappings = mappings;
        this.changes = changes;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.auditWriter = auditWriter;
        this.tx = tx;
    }

    /** 只读对比：返回逐条差异（missing_in_target / content_differs / ok）。
     *  不开事务：port.read 是外部调用不进事务（设计 ch3），快照落库由 save 自带事务。 */
    public EnvSyncSnapshot compare(String sourceEnvCode, String targetEnvCode,
                                   String appId, String envIdOfTarget, String operatorId, String traceId) {
        List<ConfigMapping> list = mappings.findByAppIdAndEnvIdOrderByDataIdAsc(appId, envIdOfTarget);
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (ConfigMapping m : list) {
            assertFormatConsistent(sourceEnvCode, m);
            ConfigRef ref = new ConfigRef(m.getNamespace(), m.getGroupName(), m.getDataId());
            String srcMd5;
            String tgtMd5;
            try {
                srcMd5 = port.read(new EnvId(sourceEnvCode), ref).md5();
            } catch (NcgException e) {
                diffs.add(Map.of("dataId", m.getDataId(), "status", "missing_in_source"));
                continue;
            }
            try {
                tgtMd5 = port.read(new EnvId(targetEnvCode), ref).md5();
            } catch (NcgException e) {
                diffs.add(Map.of("dataId", m.getDataId(), "status", "missing_in_target",
                        "sourceMd5", srcMd5));
                continue;
            }
            if (!srcMd5.equalsIgnoreCase(tgtMd5)) {
                diffs.add(Map.of("dataId", m.getDataId(), "status", "content_differs",
                        "sourceMd5", srcMd5, "targetMd5", tgtMd5));
            }
        }
        EnvSyncSnapshot snap = new EnvSyncSnapshot();
        snap.setSnapshotId(Ulid.generate());
        snap.setSourceEnv(sourceEnvCode);
        snap.setTargetEnv(targetEnvCode);
        snap.setConfigCount(list.size());
        snap.setDiffSummary(Jsonb.of("{\"diffs\":" + toJsonArray(diffs) + "}"));
        snap.setCreatedBy(operatorId);
        snap.setExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS)); // R-SYN 默认保留 90 天 [TBD]
        snap.setCreatedAt(Instant.now());
        return snapshots.save(snap);
    }

    /**
     * 提议同步：为差异项在目标环境创建 SYNC 变更单（草稿→提交走既有流程），
     * 目标已有活跃单/映射缺失则阻断该项（R-SYN-03/04）。
     */
    public List<String> propose(String snapshotId, String targetAppId, String targetEnvId,
                                String operatorId, String traceId) {
        return propose(snapshotId, targetAppId, targetEnvId, null, null, null, operatorId, traceId);
    }

    /** 契约 #34 口径：mappingIds 过滤 + title/reason（兼容旧签名的重载，行为不变）。 */
    public List<String> propose(String snapshotId, String targetAppId, String targetEnvId,
                                java.util.Set<String> mappingIds, String title, String reason,
                                String operatorId, String traceId) {
        instanceService.requireWritable(targetEnvId);
        EnvSyncSnapshot snap = snapshots.findById(snapshotId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "snapshot not found"));
        // 契约 TC-18 / R-SYN-04：显式勾选的每一项先做源/目标格式一致性校验（不依赖 diff 命中）
        if (mappingIds != null && !mappingIds.isEmpty()) {
            for (String mid : mappingIds) {
                ConfigMapping sel = mappings.findById(mid).orElse(null);
                if (sel != null) {
                    assertFormatConsistent(snap.getSourceEnv(), sel);
                }
            }
        }
        // 简化：从 diffSummary 解析出 dataId 列表创建变更单；每个数据项一条
        List<String> created = new ArrayList<>();
        String json = snap.getDiffSummary().json();
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("\"dataId\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (mm.find()) {
            String dataId = mm.group(1);
            // 联调 D9：契约 apply body 无 appId（{snapshotId,targetEnvId,mappingIds,...}），
            // appId 缺省时按勾选项 mappingIds 反查目标映射（findByAppIdAndEnvId 的 null 参数匹配不到）。
            ConfigMapping m = null;
            if (targetAppId != null && !targetAppId.isBlank()) {
                m = mappings.findByAppIdAndEnvIdOrderByDataIdAsc(targetAppId, targetEnvId)
                        .stream().filter(x -> x.getDataId().equals(dataId)).findFirst().orElse(null);
            }
            if (m == null && mappingIds != null) {
                for (String mid : mappingIds) {
                    ConfigMapping sel = mappings.findById(mid).orElse(null);
                    if (sel != null && targetEnvId.equals(sel.getEnvId())
                            && sel.getDataId().equals(dataId)) {
                        m = sel;
                        break;
                    }
                }
            }
            if (m == null) {
                continue; // 映射缺失：阻断（R-SYN-03），不创建
            }
            if (mappingIds != null && !mappingIds.isEmpty() && !mappingIds.contains(m.getMappingId())) {
                continue; // 契约：只对勾选的差异项建单
            }
            assertFormatConsistent(snap.getSourceEnv(), m);
            var active = changes.findFirstByMappingIdAndStatusIn(m.getMappingId(),
                    ChangeDraftService.ACTIVE_STATES);
            if (active.isPresent()) {
                throw new NcgException(NcgErrorCode.SYN_409_001,
                        "target mapping has active change: " + dataId);
            }
            // 读取源内容作为目标变更内容
            String content;
            try {
                content = port.read(new EnvId(snap.getSourceEnv()),
                        new ConfigRef(m.getNamespace(), m.getGroupName(), m.getDataId())).content();
            } catch (NcgException e) {
                continue;
            }
            String changeTitle = (title == null || title.isBlank())
                    ? "Sync " + dataId + " from " + snap.getSourceEnv() : title;
            String changeReason = (reason == null || reason.isBlank())
                    ? "Cross-env sync propose from snapshot " + snapshotId : reason;
            ChangeRequest cr = drafts.open(targetAppId == null || targetAppId.isBlank()
                            ? m.getAppId() : targetAppId,
                    targetEnvId, m.getMappingId(), "SYNC",
                    changeTitle, changeReason,
                    content, operatorId, null, traceId);
            created.add(cr.getChangeNo());
        }
        // 审计是 MANDATORY 传播：必须在事务内（与业务动作同事务，审计失败整笔失败）
        tx.executeWithoutResult(s -> auditWriter.append(AuditWriter.Command.user(
                operatorId, null, AuditAction.CHANGE_CREATE, "ENV_SYNC", snapshotId, traceId)));
        return created;
    }

    /**
     * R-SYN-04 / 契约 SYN-409-002：同一逻辑配置（同 dataId 或同基名不同后缀）在源/目标环境 format 不一致时阻断。
     * 源环境无对应映射或找不到源环境时跳过（missing_* 是对比结果，不是格式冲突）。
     */
    private void assertFormatConsistent(String sourceEnvCode, ConfigMapping target) {
        if (target.getFormat() == null) return;
        String sourceEnvId;
        try {
            sourceEnvId = catalogService.getEnvByCode(sourceEnvCode).getEnvId();
        } catch (RuntimeException e) {
            return;
        }
        String base = baseName(target.getDataId());
        for (ConfigMapping sm : mappings.findByAppIdAndEnvIdOrderByDataIdAsc(target.getAppId(), sourceEnvId)) {
            boolean sameConfig = sm.getDataId().equals(target.getDataId())
                    || baseName(sm.getDataId()).equals(base);
            if (sameConfig && sm.getFormat() != null && !sm.getFormat().equals(target.getFormat())) {
                throw new NcgException(NcgErrorCode.SYN_409_002,
                        "source/target config format mismatch: " + target.getDataId()
                                + " source=" + sm.getFormat() + " target=" + target.getFormat());
            }
        }
    }

    private static String baseName(String dataId) {
        int i = dataId.lastIndexOf('.');
        return i > 0 ? dataId.substring(0, i) : dataId;
    }

    private static String toJsonArray(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : list.get(i).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }
}

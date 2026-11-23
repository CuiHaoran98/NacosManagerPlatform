package com.nacos.ncg.audit.app;

import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.audit.domain.DirectChangeEvent;
import com.nacos.ncg.audit.domain.ScanCursor;
import com.nacos.ncg.audit.repo.DirectChangeEventRepository;
import com.nacos.ncg.audit.repo.ScanCursorRepository;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.notification.app.NotificationOutbox;
import com.nacos.ncg.adapter.nacos.port.ConfigRef;
import com.nacos.ncg.adapter.nacos.port.EnvId;
import com.nacos.ncg.adapter.nacos.port.HistoryEntry;
import com.nacos.ncg.adapter.nacos.port.HistoryPage;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.release.domain.ReleaseRecord;
import com.nacos.ncg.release.repo.ReleaseRecordRepository;
import com.nacos.ncg.shared.Ulid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 直连修改检测（FR-05 / 流程 7.11 / R-AUD-05/06/07）：
 * 增量拉 Nacos 历史与平台发布记录比对；未匹配→UNAUTHORIZED+审计+企微/站内告警；
 * 历史缺口→BLIND_SPOT 不推断（R-AUD-07）；同一历史记录只报警一次（uk_direct）。
 */
@Service
public class DirectScanService {

    private final NacosConfigPort port;
    private final ScanCursorRepository cursors;
    private final DirectChangeEventRepository events;
    private final ReleaseRecordRepository releases;
    private final ConfigMappingRepository mappings;
    private final AuditWriter auditWriter;
    private final NotificationOutbox outbox;
    private final TransactionTemplate tx;

    public DirectScanService(NacosConfigPort port, ScanCursorRepository cursors,
                             DirectChangeEventRepository events, ReleaseRecordRepository releases,
                             ConfigMappingRepository mappings, AuditWriter auditWriter,
                             NotificationOutbox outbox, PlatformTransactionManager txm) {
        this.port = port;
        this.cursors = cursors;
        this.events = events;
        this.releases = releases;
        this.mappings = mappings;
        this.auditWriter = auditWriter;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * 扫描一个环境（Worker 定时调用）：增量拉历史，逐条判定 MATCHED/UNAUTHORIZED；
     * 拉取失败计连续失败并标记盲区。
     */
    public void scan(String envId, String envCode) {
        ScanCursor cursor = cursors.findById(envId).orElseGet(() -> {
            ScanCursor c = new ScanCursor();
            c.setEnvId(envId);
            return c;
        });
        Instant since = cursor.getLastScanAt() == null
                ? Instant.now().minus(1, ChronoUnit.HOURS) : cursor.getLastScanAt();
        HistoryPage page;
        try {
            // 联调 D10：cursor 语义是分页位点（fake 适配器为数值偏移），不能传 lastHistoryId
            //（"H5" 会让 Integer.parseInt 抛异常导致第二次扫描静默失败）；增量性由 since+uk_direct 去重保证。
            page = port.history(new EnvId(envCode), null, since, null);
            cursor.setConsecutiveFailures(0);
            cursor.setBlindSpotSince(null);
        } catch (RuntimeException ex) {
            cursor.setConsecutiveFailures(cursor.getConsecutiveFailures() + 1);
            if (cursor.getConsecutiveFailures() >= 3 && cursor.getBlindSpotSince() == null) {
                cursor.setBlindSpotSince(Instant.now()); // R-AUD-07 历史缺口标检测盲区
            }
            cursors.save(cursor);
            return;
        }
        for (HistoryEntry entry : page.entries()) {
            Optional<DirectChangeEvent> dup = events.findByEnvIdAndDataIdAndNacosHistoryId(
                    envId, entry.dataId(), entry.historyId());
            if (dup.isPresent()) {
                continue; // 同一历史记录只报警一次
            }
            String verdict = matchRelease(envId, entry) ? "MATCHED" : "UNAUTHORIZED";
            DirectChangeEvent ev = new DirectChangeEvent();
            ev.setEventId(Ulid.generate());
            ev.setEnvId(envId);
            ev.setDataId(entry.dataId());
            ev.setNacosHistoryId(entry.historyId());
            ev.setDetectedAt(Instant.now());
            ev.setCreatedAt(Instant.now());
            ev.setVerdict(verdict);
            events.save(ev);
            if ("UNAUTHORIZED".equals(verdict)) {
                tx.executeWithoutResult(s -> {
                    auditWriter.append(AuditWriter.Command.system(AuditAction.DIRECT_CHANGE_DETECTED,
                            "CONFIG", entry.dataId(), null));
                    outbox.enqueue(NotificationOutbox.EventType.DIRECT_CHANGE_UNAUTHORIZED,
                            "CONFIG", entry.dataId(),
                            Map.of("envId", envId, "historyId", entry.historyId(),
                                    "modifiedBy", String.valueOf(entry.modifiedBy())),
                            "WECOM");
                });
            }
        }
        cursor.setLastScanAt(Instant.now());
        if (!page.entries().isEmpty()) {
            cursor.setLastHistoryId(page.entries().get(page.entries().size() - 1).historyId());
        }
        cursors.save(cursor);
    }

    /**
     * 匹配发布记录：同环境+dataId，发布记录 readback_md5 与历史 md5 一致且
     * 时间窗 ±5 分钟内视为平台写入（R-AUD-05）。
     */
    private boolean matchRelease(String envId, HistoryEntry entry) {
        List<ConfigMapping> list = mappings.findByStatusOrderByDataIdAsc("MANAGED");
        for (ConfigMapping m : list) {
            if (!m.getDataId().equals(entry.dataId()) || !m.getEnvId().equals(envId)) {
                continue;
            }
            for (ReleaseRecord r : releases.findByMappingIdOrderByStartedAtDesc(m.getMappingId())) {
                if (r.getReadbackMd5() != null && r.getReadbackMd5().equalsIgnoreCase(entry.md5())
                        && r.getFinishedAt() != null
                        && Math.abs(r.getFinishedAt().until(entry.modifiedAt(), ChronoUnit.MINUTES)) <= 5) {
                    return true;
                }
            }
        }
        return false;
    }
}

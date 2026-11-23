package com.nacos.ncg.audit.app;

import com.nacos.ncg.audit.domain.AuditEvent;
import com.nacos.ncg.audit.repo.AuditEventRepository;
import com.nacos.ncg.shared.HashChain;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计链校验（R-AUD-05/06 / §10.3）：按日重算哈希链，
 * 断链只告警不自动修复（§13）。每日由 Worker 调度，也可 API 触发。
 */
@Service
public class AuditChainService {

    private final AuditEventRepository repository;

    public AuditChainService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public record Report(LocalDate chainDate, long count, String firstHash, String lastHash,
                         boolean ok, Long brokenAtSeq) { }

    @Transactional(readOnly = true)
    public Report verify(LocalDate chainDate) {
        List<AuditEvent> events = repository.findByChainDateOrderBySeqAsc(chainDate);
        if (events.isEmpty()) {
            return new Report(chainDate, 0, null, null, true, null);
        }
        String prevHash = HashChain.GENESIS;
        long expectedSeq = 1L;
        for (AuditEvent e : events) {
            if (e.getSeq() != expectedSeq) {
                return new Report(chainDate, events.size(),
                        events.get(0).getEventHash(), events.get(events.size() - 1).getEventHash(),
                        false, e.getSeq());
            }
            Map<String, String> fields = new TreeMap<>(HashChain.eventFields(
                    e.getChainDate().toString(), e.getSeq(),
                    e.getOccurredAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    e.getActorType(), e.getActorId(), e.getAction(),
                    e.getObjectType(), e.getObjectId(), e.getResult(), e.getTraceId()));
            if (e.getBeforeMasked() != null) fields.put("before_masked", e.getBeforeMasked().json());
            if (e.getAfterMasked() != null) fields.put("after_masked", e.getAfterMasked().json());
            String expectedHash = HashChain.eventHash(prevHash, fields);
            if (!expectedHash.equals(e.getEventHash()) || !prevHash.equals(e.getPrevHash())) {
                return new Report(chainDate, events.size(),
                        events.get(0).getEventHash(), events.get(events.size() - 1).getEventHash(),
                        false, e.getSeq());
            }
            prevHash = e.getEventHash();
            expectedSeq++;
        }
        return new Report(chainDate, events.size(),
                events.get(0).getEventHash(), events.get(events.size() - 1).getEventHash(),
                true, null);
    }
}

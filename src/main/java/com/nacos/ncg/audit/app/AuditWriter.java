package com.nacos.ncg.audit.app;

import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.audit.domain.AuditEvent;
import com.nacos.ncg.audit.repo.AuditEventRepository;
import com.nacos.ncg.shared.HashChain;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.json.Jsonb;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计写入器（§5.5 / §10.2 / C-ARC-06）：
 * 1) append 加入调用方事务（REQUIRED）：状态变更与审计必须同事务，审计失败业务整笔失败（AUD-500-001）；
 * 2) 当日 seq 递增 + prev_hash 接链，写入前用 pg_advisory_xact_lock 串行化当日链尾分配；
 * 3) 事件哈希按 canonical JSON 计算（{@link HashChain}）；表触发器拒绝 UPDATE/DELETE。
 */
@Service
public class AuditWriter {

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbc;

    public AuditWriter(AuditEventRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** 审计命令（调用方给出脱敏前后值，敏感字段禁止明文）。 */
    public record Command(
            ActorType actorType,
            String actorId,
            String sourceIp,
            AuditAction action,
            String objectType,
            String objectId,
            Jsonb beforeMasked,
            Jsonb afterMasked,
            boolean success,
            String errorCode,
            String changeNo,
            String traceId) {

        public static Command user(String actorId, String sourceIp, AuditAction action,
                                   String objectType, String objectId, String traceId) {
            return new Command(ActorType.USER, actorId, sourceIp, action, objectType, objectId,
                    null, null, true, null, null, traceId);
        }

        public static Command system(AuditAction action, String objectType, String objectId, String traceId) {
            return new Command(ActorType.SYSTEM, "system", null, action, objectType, objectId,
                    null, null, true, null, null, traceId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent append(Command cmd) {
        // 毫秒截断：PG timestamptz 只存微秒，写哈希与读回必须同一字符串（否则链校验必断）
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String traceId = com.nacos.ncg.shared.TraceIds.orNew(cmd.traceId());
        LocalDate chainDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();

        // 串行化当日链尾分配（事务级锁，随事务释放）
        jdbc.execute("SELECT pg_advisory_xact_lock((" + Math.abs(chainDate.toString().hashCode()) + ")::bigint)");

        AuditEvent last = repository.findFirstByChainDateOrderBySeqDesc(chainDate).orElse(null);
        long seq = last == null ? 1L : last.getSeq() + 1;
        String prevHash = last == null ? HashChain.GENESIS : last.getEventHash();

        Map<String, String> fields = HashChain.eventFields(
                chainDate.toString(), seq, occurredAt,
                cmd.actorType().name(), cmd.actorId(), cmd.action().name(),
                cmd.objectType(), cmd.objectId(),
                cmd.success() ? "SUCCESS" : "FAILURE", traceId);
        if (cmd.beforeMasked() != null) {
            fields.put("before_masked", cmd.beforeMasked().json());
        }
        if (cmd.afterMasked() != null) {
            fields.put("after_masked", cmd.afterMasked().json());
        }
        // 附加字段一并入哈希（字段序由 TreeMap 规范）
        Map<String, String> hashInput = new TreeMap<>(fields);
        String eventHash = HashChain.eventHash(prevHash, hashInput);

        AuditEvent event = new AuditEvent();
        event.setEventId(Ulid.generate(occurredAt));
        event.setChainDate(chainDate);
        event.setSeq(seq);
        event.setOccurredAt(occurredAt);
        event.setActorType(cmd.actorType().name());
        // 审计 NOT NULL 列兜底：缺失主体/对象不得让审计写入崩溃（联调 D6：无用户名登录失败审计 object_id null → 500）
        event.setActorId(cmd.actorId() == null || cmd.actorId().isBlank() ? "unknown" : cmd.actorId());
        event.setSourceIp(cmd.sourceIp());
        event.setAction(cmd.action().name());
        event.setObjectType(cmd.objectType() == null ? "UNKNOWN" : cmd.objectType());
        event.setObjectId(cmd.objectId() == null || cmd.objectId().isBlank() ? "-" : cmd.objectId());
        event.setBeforeMasked(cmd.beforeMasked());
        event.setAfterMasked(cmd.afterMasked());
        event.setResult(cmd.success() ? "SUCCESS" : "FAILURE");
        event.setErrorCode(cmd.errorCode());
        event.setChangeNo(cmd.changeNo());
        event.setTraceId(traceId);
        event.setPrevHash(prevHash);
        event.setEventHash(eventHash);
        return repository.save(event);
    }
}

package com.nacos.ncg.catalog.app;

import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.catalog.domain.Instance;
import com.nacos.ncg.catalog.repo.InstanceRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 实例注册与状态（E.1.7 / R-CON-01~05 / FR-10）：
 * 一环境只绑一实例（uk_instance_env 捕获冲突→CON-409-001）；
 * 探活结果驱动 ONLINE/DEGRADED/UNAVAILABLE；实例恢复不补发历史操作（§13）。
 */
@Service
public class InstanceService {

    private final InstanceRepository instances;
    private final AuditWriter auditWriter;

    public InstanceService(InstanceRepository instances, AuditWriter auditWriter) {
        this.instances = instances;
        this.auditWriter = auditWriter;
    }

    /** 注册/更新某环境的 Nacos 实例。credential 传明文会立即加密存储，界面永不回显。 */
    @Transactional
    public Instance register(String envId, String endpoint, byte[] credentialCiphertext,
                             String operatorId, String traceId) {
        Instance existing = instances.findByEnvId(envId).orElse(null);
        // 一环境一实例由 uk_instance_env 兜底（冲突→CON-409-001）
        Instance instance = existing != null ? existing : new Instance();
        if (existing == null) {
            instance.setInstanceId(Ulid.generate());
            instance.setEnvId(envId);
            instance.setStatus("ONLINE");
            instance.setConsecutiveFailures(0);
            instance.setCreatedAt(Instant.now());
        }
        instance.setEndpoint(endpoint);
        if (credentialCiphertext != null) {
            instance.setCredentialCiphertext(credentialCiphertext);
        }
        instance.setUpdatedAt(Instant.now());
        Instance saved = instances.save(instance);
        auditWriter.append(new AuditWriter.Command(
                ActorType.USER, operatorId, null, AuditAction.INSTANCE_TEST,
                "INSTANCE", saved.getInstanceId(), null, null, true, null, null, traceId));
        return saved;
    }

    /** 记录一次探活结果（探活任务调用）；状态迁移见 E.2.7。 */
    @Transactional
    public Instance recordProbe(String envId, boolean reachable, long latencyMs, String traceId) {
        Instance instance = instances.findByEnvId(envId).orElse(null);
        if (instance == null) {
            // 未登记实例无探活对象（R-CON-03 实例登记后才有探活意义），静默跳过
            return null;
        }
        String before = instance.getStatus();
        instance.setLastProbeAt(Instant.now());
        instance.setLastProbeLatencyMs((int) latencyMs);
        if (reachable) {
            instance.setConsecutiveFailures(0);
            instance.setStatus("ONLINE");
        } else {
            instance.setConsecutiveFailures(instance.getConsecutiveFailures() + 1);
            // 连续 3 次失败判不可用（附录 F 探活参数 [TBD]）
            instance.setStatus(instance.getConsecutiveFailures() >= 3 ? "UNAVAILABLE" : "DEGRADED");
        }
        instance.setUpdatedAt(Instant.now());
        Instance saved = instances.save(instance);
        if (!before.equals(saved.getStatus())) {
            auditWriter.append(new AuditWriter.Command(
                    ActorType.SYSTEM, "system", null, AuditAction.INSTANCE_STATUS_CHANGE,
                    "INSTANCE", saved.getInstanceId(), null, null, true, null, null, traceId));
        }
        return saved;
    }

    public java.util.List<Instance> listInstances() {
        return java.util.stream.StreamSupport.stream(instances.findAll().spliterator(), false).toList();
    }

    /**
     * 环境只读降级守卫（设计 §13 / §6.7）：实例 UNAVAILABLE 时该环境写接口返回 SYS-423-001；
     * 发布/回滚/恢复仍走各自的 REL-503-001 预检。未登记实例的环境不校验（探活未启用）。
     */
    public void requireWritable(String envId) {
        instances.findByEnvId(envId).ifPresent(inst -> {
            if ("UNAVAILABLE".equals(inst.getStatus())) {
                throw new com.nacos.ncg.shared.NcgException(com.nacos.ncg.shared.NcgErrorCode.SYS_423_001,
                        "env read-only degraded: instance unavailable, waiting for recovery");
            }
        });
    }
}

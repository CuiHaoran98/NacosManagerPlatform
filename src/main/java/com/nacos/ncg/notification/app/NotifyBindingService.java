package com.nacos.ncg.notification.app;

import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.notification.domain.NotifyBinding;
import com.nacos.ncg.notification.repo.NotifyBindingRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import com.nacos.ncg.shared.Ulid;
import com.nacos.ncg.shared.crypto.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 通知绑定管理（§11.3 / FR-09）：应用×环境×渠道绑定企微群机器人；
 * webhook 明文只在写入时出现，落库即加密（target_ciphertext）。
 */
@Service
public class NotifyBindingService {

    private final NotifyBindingRepository bindings;
    private final CryptoService crypto;
    private final AuditWriter auditWriter;
    private final TransactionTemplate tx;

    public NotifyBindingService(NotifyBindingRepository bindings, CryptoService crypto,
                                AuditWriter auditWriter, PlatformTransactionManager transactionManager) {
        this.bindings = bindings;
        this.crypto = crypto;
        this.auditWriter = auditWriter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<NotifyBinding> list(String appId, String envId) {
        return bindings.findByAppIdAndEnvId(appId, envId);
    }

    /** 契约 #38 无参列表：全量绑定（调用方自行过滤）。 */
    public List<NotifyBinding> listAll() {
        List<NotifyBinding> all = new java.util.ArrayList<>();
        bindings.findAll().forEach(all::add);
        return all;
    }

    /** upsert：同 (app_id, env_id, channel) 覆盖更新；channel=IN_APP 时 target 可空。 */
    public NotifyBinding upsert(String appId, String envId, String channel, String target,
                                String operatorId, String traceId) {
        if (!"IN_APP".equals(channel) && !"WECOM".equals(channel)) {
            throw new NcgException(NcgErrorCode.CR_400_001, "channel must be IN_APP or WECOM");
        }
        if ("WECOM".equals(channel) && (target == null || target.isBlank())) {
            throw new NcgException(NcgErrorCode.CR_400_001, "wecom binding requires a webhook target");
        }
        Instant now = Instant.now();
        return tx.execute(s -> {
            NotifyBinding binding = bindings.findByAppIdAndEnvIdAndChannel(appId, envId, channel)
                    .orElseGet(() -> {
                        NotifyBinding nb = new NotifyBinding();
                        nb.setBindingId(Ulid.generate());
                        nb.setAppId(appId);
                        nb.setEnvId(envId);
                        nb.setChannel(channel);
                        nb.setCreatedAt(now);
                        return nb;
                    });
            binding.setTargetCiphertext(target == null || target.isBlank() ? null : crypto.encrypt(target));
            binding.setStatus("ENABLED");
            binding.setUpdatedAt(now);
            NotifyBinding saved = bindings.save(binding);
            auditWriter.append(AuditWriter.Command.user(operatorId, null,
                    AuditAction.GRANT_CHANGE, "notify_binding", saved.getBindingId(), traceId));
            return saved;
        });
    }

    public void delete(String bindingId, String operatorId, String traceId) {
        NotifyBinding binding = bindings.findById(bindingId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001,
                        "notify binding not found: " + bindingId));
        tx.executeWithoutResult(s -> {
            bindings.delete(binding);
            auditWriter.append(AuditWriter.Command.user(operatorId, null,
                    AuditAction.GRANT_CHANGE, "notify_binding", bindingId, traceId));
        });
    }
}

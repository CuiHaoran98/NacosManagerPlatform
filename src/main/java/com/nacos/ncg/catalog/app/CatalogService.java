package com.nacos.ncg.catalog.app;

import com.nacos.ncg.catalog.domain.App;
import com.nacos.ncg.catalog.domain.ConfigMapping;
import com.nacos.ncg.catalog.domain.Env;
import com.nacos.ncg.catalog.repo.AppRepository;
import com.nacos.ncg.catalog.repo.ConfigMappingRepository;
import com.nacos.ncg.catalog.repo.EnvRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 只读目录服务（FR-01 / R-CAT-01~04）：应用→环境→配置分组→配置项；
 * 未纳管清单（status=UNMANAGED）；敏感标记驱动脱敏（调用方决定明文是否可给）。
 * 只读路径不写 Nacos；目录内容本身来自平台登记的映射。
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final AppRepository apps;
    private final EnvRepository envs;
    private final ConfigMappingRepository mappings;
    private final com.nacos.ncg.audit.app.AuditWriter auditWriter;

    public CatalogService(AppRepository apps, EnvRepository envs, ConfigMappingRepository mappings,
                          com.nacos.ncg.audit.app.AuditWriter auditWriter) {
        this.apps = apps;
        this.envs = envs;
        this.mappings = mappings;
        this.auditWriter = auditWriter;
    }

    public List<App> listApps() {
        return apps.findAllByOrderByAppCodeAsc();
    }

    public App getApp(String appId) {
        return apps.findById(appId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "app not found: " + appId));
    }

    public List<Env> listEnvs() {
        return envs.findAllByOrderBySortOrderAsc();
    }

    public Env getEnv(String envId) {
        return envs.findById(envId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "env not found: " + envId));
    }

    public Env getEnvByCode(String envCode) {
        return envs.findByEnvCode(envCode)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "env not found: " + envCode));
    }

    /** 应用×环境下的配置映射列表（目录树的配置项层；DELETED 进回收站不出现在目录）。 */
    public List<ConfigMapping> listMappings(String appId, String envId) {
        return mappings.findByAppIdAndEnvIdAndStatusNotOrderByDataIdAsc(appId, envId, "DELETED");
    }

    /** 未纳管清单：已发现但未纳入治理的配置。 */
    public List<ConfigMapping> listUnmanaged() {
        return mappings.findByStatusOrderByDataIdAsc("UNMANAGED");
    }

    /** 按物理坐标解析映射（namespace+group+dataId 全局唯一）。 */
    public ConfigMapping resolve(String namespace, String groupName, String dataId) {
        return mappings.findFirstByNamespaceAndGroupNameAndDataIdAndStatusNot(namespace, groupName, dataId, "DELETED")
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001,
                        "config mapping not found: " + namespace + "/" + groupName + "/" + dataId));
    }

    public ConfigMapping getMapping(String mappingId) {
        return mappings.findById(mappingId)
                .orElseThrow(() -> new NcgException(NcgErrorCode.SYS_404_001, "mapping not found: " + mappingId));
    }

    /** 发布成功后回写映射快照（仅由发布编排调用，C-ARC-03）。 */
    @Transactional(readOnly = false)
    public ConfigMapping updateSnapshot(String mappingId, String md5, Instant collectedAt) {
        ConfigMapping mapping = getMapping(mappingId);
        mapping.setLastSnapshotMd5(md5);
        mapping.setLastCollectedAt(collectedAt);
        return mappings.save(mapping);
    }

    // ---- 映射登记（FR-01 / §6.2 POST|PUT /mappings、POST /mappings/import）----

    @Transactional
    public ConfigMapping createMapping(String appId, String envId, String namespace, String groupName,
                                       String dataId, boolean sensitive, String operatorId, String traceId) {
        App app = getApp(appId);
        getEnv(envId);
        if (mappings.existsByNamespaceAndGroupNameAndDataIdAndStatusNot(namespace, groupName, dataId, "DELETED")) {
            throw new NcgException(NcgErrorCode.CAT_409_001, "mapping already exists: "
                    + namespace + "/" + groupName + "/" + dataId);
        }
        ConfigMapping m = new ConfigMapping();
        m.setMappingId(com.nacos.ncg.shared.Ulid.generate());
        m.setAppId(appId);
        m.setEnvId(envId);
        m.setNamespace(namespace);
        m.setGroupName(groupName);
        m.setDataId(dataId);
        // 格式纳管固化（MUST-15）：按 dataId 后缀写入，此后不可变
        m.setFormat(com.nacos.ncg.shared.content.ConfigContentCodec.formatOfDataId(dataId));
        m.setSensitive(sensitive);
        // 负责人默认继承应用负责人（R-CAT-01 映射归属应用；owner_id NOT NULL）
        m.setOwnerId(app.getOwnerId() != null ? app.getOwnerId() : operatorId);
        m.setStatus("MANAGED");
        Instant now = Instant.now();
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        ConfigMapping saved = mappings.save(m);
        audit(com.nacos.ncg.audit.domain.AuditAction.MAPPING_CREATE, operatorId, traceId, saved);
        return saved;
    }

    @Transactional
    public ConfigMapping updateMapping(String mappingId, String appId, String envId, boolean sensitive,
                                       String operatorId, String traceId) {
        ConfigMapping m = getMapping(mappingId);
        if (appId != null && !appId.equals(m.getAppId())) {
            getApp(appId);
            m.setAppId(appId);
        }
        if (envId != null && !envId.equals(m.getEnvId())) {
            getEnv(envId);
            m.setEnvId(envId);
        }
        m.setSensitive(sensitive);
        m.setUpdatedAt(Instant.now());
        ConfigMapping saved = mappings.save(m);
        audit(com.nacos.ncg.audit.domain.AuditAction.MAPPING_UPDATE, operatorId, traceId, saved);
        return saved;
    }

    /** 契约补充（向后兼容加法）：ownerId 可选更新（PUT /mappings/{id} 的 owner 字段）。 */
    @Transactional
    public ConfigMapping updateMapping(String mappingId, String appId, String envId, boolean sensitive,
                                       String ownerId, String operatorId, String traceId) {
        ConfigMapping saved = updateMapping(mappingId, appId, envId, sensitive, operatorId, traceId);
        if (ownerId != null && !ownerId.isBlank() && !ownerId.equals(saved.getOwnerId())) {
            saved.setOwnerId(ownerId);
            saved.setUpdatedAt(Instant.now());
            saved = mappings.save(saved);
            audit(com.nacos.ncg.audit.domain.AuditAction.MAPPING_UPDATE, operatorId, traceId, saved);
        }
        return saved;
    }

    /** 纳管：把 UNMANAGED 映射转 MANAGED（CAT-409-002 纳管与现有映射冲突）。 */
    @Transactional
    public ConfigMapping importMapping(String namespace, String groupName, String dataId,
                                       String appId, String envId, String operatorId, String traceId) {
        getApp(appId);
        getEnv(envId);
        var existing = mappings.findFirstByNamespaceAndGroupNameAndDataIdAndStatusNot(namespace, groupName, dataId, "DELETED");
        if (existing.isPresent()) {
            ConfigMapping m = existing.get();
            if ("MANAGED".equals(m.getStatus())) {
                throw new NcgException(NcgErrorCode.CAT_409_002,
                        "import conflicts with an existing managed mapping");
            }
            m.setStatus("MANAGED");
            m.setAppId(appId);
            m.setEnvId(envId);
            m.setUpdatedAt(Instant.now());
            ConfigMapping saved = mappings.save(m);
            audit(com.nacos.ncg.audit.domain.AuditAction.MAPPING_IMPORT, operatorId, traceId, saved);
            return saved;
        }
        ConfigMapping created = createMapping(appId, envId, namespace, groupName, dataId,
                false, operatorId, traceId);
        audit(com.nacos.ncg.audit.domain.AuditAction.MAPPING_IMPORT, operatorId, traceId, created);
        return created;
    }

    private void audit(com.nacos.ncg.audit.domain.AuditAction action, String operatorId,
                       String traceId, ConfigMapping m) {
        auditWriter.append(com.nacos.ncg.audit.app.AuditWriter.Command.user(
                operatorId, null, action, "mapping", m.getMappingId(), traceId));
    }

    /** 应用下全部映射（不限环境，§6.2 GET /apps/{appId}/configs；DELETED 进回收站不出现在目录）。 */
    public List<ConfigMapping> listMappingsByApp(String appId) {
        getApp(appId);
        return mappings.findByAppIdAndStatusNotOrderByDataIdAsc(appId, "DELETED");
    }
}

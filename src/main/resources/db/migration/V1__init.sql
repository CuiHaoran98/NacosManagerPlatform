-- NCG 初始 schema（详细设计 v1.1 §5 + spec v1.3 附录 E.1）
-- 约定：单库单 schema；主键 varchar(26) ULID（change_no 等业务编号除外）；timestamptz UTC；
-- 乐观锁 version bigint；敏感列 *_ciphertext 不存明文；不用外键级联删除，删除一律软删/状态标记。
-- 物理名映射（spec E.0：语义与约束不变）：tongncs_receipt → nacos_receipt，tongncs_history_id → nacos_history_id。

-- ============ 账号与授权 ============

CREATE TABLE ncg_account (
    account_id       varchar(26)   PRIMARY KEY,
    login_name       varchar(64)   NOT NULL,
    display_name     varchar(64)   NOT NULL,
    password_hash    varchar(255)  NOT NULL,
    status           varchar(16)   NOT NULL DEFAULT 'ENABLED',
    failed_attempts  int           NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    is_admin         boolean       NOT NULL DEFAULT false,
    last_login_at    timestamptz,
    version          bigint        NOT NULL DEFAULT 0,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_account_login UNIQUE (login_name)
);

CREATE TABLE ncg_user_group (
    group_id     varchar(26)   PRIMARY KEY,
    group_code   varchar(64)   NOT NULL,
    group_name   varchar(64)   NOT NULL,
    description  varchar(500),
    created_by   varchar(26)   NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_group_code UNIQUE (group_code)
);

CREATE TABLE ncg_group_member (
    group_id    varchar(26)  NOT NULL,
    account_id  varchar(26)  NOT NULL,
    added_by    varchar(26)  NOT NULL,
    added_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_group_member UNIQUE (group_id, account_id)
);

CREATE TABLE ncg_grant (
    grant_id      varchar(26)   PRIMARY KEY,
    subject_type  varchar(16)   NOT NULL,  -- USER / GROUP / ROLE_TEMPLATE
    subject_id    varchar(64)   NOT NULL,
    app_id        varchar(26)   NOT NULL,
    env_id        varchar(26)   NOT NULL,
    permission    varchar(32)   NOT NULL,  -- 固定 10 个权限点（R-AUTH-02）
    granted_by    varchar(26)   NOT NULL,
    expires_at    timestamptz,             -- 可空；到期即失效（R-AUTH-05）
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_grant UNIQUE (subject_type, subject_id, app_id, env_id, permission)
);

-- ============ 应用 / 环境 / 实例 / 映射 ============

CREATE TABLE ncg_app (
    app_id       varchar(26)   PRIMARY KEY,
    app_code     varchar(64)   NOT NULL,
    name         varchar(120)  NOT NULL,
    owner_id     varchar(26)   NOT NULL,
    description  varchar(500),
    status       varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    version      bigint        NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_code UNIQUE (app_code)
);

CREATE TABLE ncg_env (
    env_id                varchar(26)  PRIMARY KEY,
    env_code              varchar(16)  NOT NULL,  -- dev / staging / prod
    display_name          varchar(64)  NOT NULL,
    sort_order            int          NOT NULL,
    release_window_start  time,                   -- 空=全天
    release_window_end    time,
    status                varchar(16)  NOT NULL DEFAULT 'ENABLED',
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_env_code UNIQUE (env_code),
    CONSTRAINT ck_env_window CHECK ((release_window_start IS NULL) = (release_window_end IS NULL))
);

CREATE TABLE ncg_instance (
    instance_id             varchar(26)   PRIMARY KEY,
    env_id                  varchar(26)   NOT NULL,   -- 唯一：一环境只绑一实例（R-CON-01/04）
    endpoint                varchar(512)  NOT NULL,
    credential_ciphertext   bytea,                    -- 加密存储，界面不回显（R-CON-02、C-ARC-12）
    status                  varchar(16)   NOT NULL DEFAULT 'ONLINE',  -- ONLINE / DEGRADED / UNAVAILABLE
    consecutive_failures    int           NOT NULL DEFAULT 0,
    last_probe_at           timestamptz,
    last_probe_latency_ms   int,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_instance_env UNIQUE (env_id)
);

CREATE TABLE ncg_config_mapping (
    mapping_id          varchar(26)   PRIMARY KEY,
    app_id              varchar(26)   NOT NULL,
    env_id              varchar(26)   NOT NULL,
    namespace           varchar(128)  NOT NULL,
    group_name          varchar(128)  NOT NULL,
    data_id             varchar(128)  NOT NULL,
    sensitive           boolean       NOT NULL DEFAULT false,
    owner_id            varchar(26)   NOT NULL,
    status              varchar(16)   NOT NULL DEFAULT 'MANAGED',  -- MANAGED / UNMANAGED / DELETED
    last_snapshot_md5   char(32),
    last_collected_at   timestamptz,
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now()
    /* uk_mapping_ref 改为下方部分唯一索引：DELETED 墓碑不占名（AC-16 同名替换可建，restore 时冲突可判） */
);

CREATE UNIQUE INDEX uk_mapping_ref ON ncg_config_mapping (namespace, group_name, data_id) WHERE status <> 'DELETED';
CREATE INDEX idx_mapping_app_env ON ncg_config_mapping (app_id, env_id);

-- ============ 变更单 / 审批 / 发布记录 ============

CREATE TABLE ncg_change_request (
    change_no               varchar(24)   PRIMARY KEY,  -- CR-YYYYMMDD-NNNN
    app_id                  varchar(26)   NOT NULL,
    env_id                  varchar(26)   NOT NULL,
    mapping_id              varchar(26)   NOT NULL,
    change_type             varchar(16)   NOT NULL,     -- CREATE/UPDATE/DELETE/RESTORE/ROLLBACK/SYNC
    title                   varchar(120)  NOT NULL,
    reason                  varchar(2000) NOT NULL,     -- ≥10 字（R-CR-01，应用层校验）
    content_ciphertext      bytea,                     -- CREATE/UPDATE 必填
    content_sha256          char(64)      NOT NULL,
    baseline_md5            char(32),                  -- 提交时冻结，不可回写（R-CR-04）
    baseline_snapshot_ref   varchar(26),
    impact_snapshot         jsonb,                     -- 提交时冻结（R-IMP-01）
    risk_level              varchar(8)    NOT NULL DEFAULT 'LOW',  -- LOW / MEDIUM / HIGH
    risk_factors            jsonb        NOT NULL,
    status                  varchar(24)   NOT NULL DEFAULT 'DRAFT',
    version                 bigint        NOT NULL DEFAULT 0,
    submitter_id            varchar(26)   NOT NULL,
    publisher_id            varchar(26),               -- 发布前必须确定
    schedule_at             timestamptz,               -- SCHEDULED 必填（R-SCH-01）
    trace_id                varchar(32)   NOT NULL,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    closed_at               timestamptz                -- 仅终态写入
);
-- 同映射最多 1 个非草稿非终态单（E.2.1）：状态迁移并发控制的一部分
CREATE UNIQUE INDEX uk_active_change ON ncg_change_request (mapping_id)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'SCHEDULED', 'RELEASING', 'INDETERMINATE');

CREATE TABLE ncg_approval (
    approval_id           varchar(26)   PRIMARY KEY,
    change_no             varchar(24)   NOT NULL,
    actor_id              varchar(26)   NOT NULL,
    action                varchar(16)   NOT NULL,      -- APPROVE / REJECT / TRANSFER
    reason                varchar(2000),               -- REJECT 必填且 ≥10 字（APR-422-001）
    target_approver_id    varchar(26),                 -- TRANSFER 必填
    trace_id              varchar(32)   NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_change ON ncg_approval (change_no);

CREATE TABLE ncg_release_record (
    release_id        varchar(26)   PRIMARY KEY,
    release_key       varchar(80)   NOT NULL,          -- change_no + ':' + target_version（R-REL-04）
    change_no         varchar(24)   NOT NULL,
    mapping_id        varchar(26)   NOT NULL,
    env_id            varchar(26)   NOT NULL,
    source_type       varchar(16)   NOT NULL,          -- MANUAL/SCHEDULED/BATCH/ROLLBACK/RESTORE
    target_version    varchar(64)   NOT NULL,
    outcome           varchar(16)   NOT NULL DEFAULT 'CREATED',  -- CREATED/WRITING/VERIFIED/REJECTED/INDETERMINATE
    reject_reason     varchar(16),                     -- 仅 REJECTED：BASELINE/AUTH/UNAVAILABLE/INVALID
    expected_md5      char(32)      NOT NULL,
    readback_md5      char(32),                        -- VERIFIED 时非空且 = expected_md5
    nacos_receipt     jsonb,                           -- 适配器回执脱敏（原 tongncs_receipt）
    error_code        varchar(32),                     -- 失败必填（E.3）
    operator_id       varchar(26),                     -- 手工发布必填
    system_actor_id   varchar(26),                     -- 定时/批量代执行必填（R-SCH-03）
    trace_id          varchar(32)   NOT NULL,
    started_at        timestamptz   NOT NULL DEFAULT now(),
    finished_at       timestamptz,                     -- 仅终态写入
    CONSTRAINT uk_release_key UNIQUE (release_key)
);
CREATE INDEX idx_release_change ON ncg_release_record (change_no);

-- ============ 审计（append-only 按日哈希链，C-ARC-11 / R-AUD-03） ============

CREATE TABLE ncg_audit_event (
    event_id        varchar(26)   PRIMARY KEY,
    chain_date      date          NOT NULL,            -- UTC 日分链
    seq             bigint        NOT NULL,            -- 当日从 1 递增
    occurred_at     timestamptz   NOT NULL,
    actor_type      varchar(8)    NOT NULL,            -- USER / SYSTEM
    actor_id        varchar(64)   NOT NULL,
    source_ip       varchar(45),                       -- 用户操作必填；系统任务填执行节点
    action          varchar(48)   NOT NULL,            -- 固定枚举（§10.5），新增需版本化
    object_type     varchar(32)   NOT NULL,
    object_id       varchar(64)   NOT NULL,
    before_masked   jsonb,                             -- 敏感字段脱敏，禁止明文
    after_masked    jsonb,
    result          varchar(8)    NOT NULL,            -- SUCCESS / FAILURE
    error_code      varchar(32),                       -- FAILURE 必填
    change_no       varchar(24),
    trace_id        varchar(32)   NOT NULL,
    prev_hash       char(64)      NOT NULL,            -- 当日首条 = 64 个 0
    event_hash      char(64)      NOT NULL,
    export_ref      varchar(64),                       -- 导出事件回填引用（E.1.3）
    CONSTRAINT uk_audit_chain UNIQUE (chain_date, seq)
);
CREATE INDEX idx_audit_time ON ncg_audit_event (occurred_at);

-- 审计表只增不改：触发器拒绝 UPDATE/DELETE（数据库账号另只授 INSERT/SELECT）
CREATE FUNCTION ncg_audit_event_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ncg_audit_event is append-only (C-ARC-11)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_no_update
    BEFORE UPDATE OR DELETE ON ncg_audit_event
    FOR EACH ROW EXECUTE FUNCTION ncg_audit_event_block_mutation();

-- ============ 定时 / 批量 ============

CREATE TABLE ncg_schedule_job (
    job_id             varchar(26)   PRIMARY KEY,
    change_no          varchar(24)   NOT NULL,
    execute_at         timestamptz   NOT NULL,         -- 改期只改此字段（R-SCH-01）
    status             varchar(16)   NOT NULL DEFAULT 'SCHEDULED',  -- E.2.3
    lease_owner        varchar(128),
    lease_expires_at   timestamptz,
    attempts           int           NOT NULL DEFAULT 0,
    last_error_code    varchar(32),
    created_by         varchar(26)   NOT NULL,
    trace_id           varchar(32)   NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now()
);
-- 同一变更单最多 1 个活动任务（E.2.3）
CREATE UNIQUE INDEX uk_schedule_change ON ncg_schedule_job (change_no)
    WHERE status IN ('SCHEDULED', 'TRIGGERED', 'PRECHECK', 'DISPATCHED');

CREATE TABLE ncg_batch (
    batch_id           varchar(26)   PRIMARY KEY,
    parent_change_no   varchar(24)   NOT NULL,
    status             varchar(16)   NOT NULL DEFAULT 'CREATED',  -- E.2.4
    total_count        int           NOT NULL,
    succeeded_count    int           NOT NULL DEFAULT 0,
    failed_count       int           NOT NULL DEFAULT 0,
    created_by         varchar(26)   NOT NULL,
    trace_id           varchar(32)   NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_batch_change UNIQUE (parent_change_no)
);

CREATE TABLE ncg_batch_item (
    item_id       varchar(26)   PRIMARY KEY,
    batch_id      varchar(26)   NOT NULL,
    change_no     varchar(24)   NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED
    release_id    varchar(26),
    error_code    varchar(32),
    finished_at   timestamptz,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_batch_item UNIQUE (batch_id, change_no)  -- 成功项不重发（R-SCH-05）
);

-- ============ 通知（发件箱） ============

CREATE TABLE ncg_notification (
    notification_id   varchar(26)   PRIMARY KEY,
    channel           varchar(16)   NOT NULL,          -- IN_APP / WECOM
    event_type        varchar(48)   NOT NULL,
    object_type       varchar(32)   NOT NULL,
    object_id         varchar(64)   NOT NULL,
    payload_masked    jsonb         NOT NULL,          -- 仅对象/动作/结果/操作人/链接（R-NTF-03）
    dedup_key         varchar(128)  NOT NULL,          -- 事件类型+对象+聚合窗口（R-NTF-05）
    status            varchar(16)   NOT NULL DEFAULT 'QUEUED',  -- QUEUED/SENDING/SENT/RETRYING/DEAD
    retry_count       int           NOT NULL DEFAULT 0,
    next_retry_at     timestamptz,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_status ON ncg_notification (status, next_retry_at);
CREATE INDEX idx_notification_dedup ON ncg_notification (dedup_key);

CREATE TABLE ncg_notify_binding (
    binding_id          varchar(26)   PRIMARY KEY,
    app_id              varchar(26)   NOT NULL,
    env_id              varchar(26)   NOT NULL,
    channel             varchar(16)   NOT NULL,        -- IN_APP / WECOM
    target_ciphertext   bytea,                         -- Webhook 密文；IN_APP 可空
    status              varchar(16)   NOT NULL DEFAULT 'ENABLED',
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_notify_binding UNIQUE (app_id, env_id, channel)
);

-- ============ 直连检测 ============

CREATE TABLE ncg_direct_change_event (
    event_id              varchar(26)   PRIMARY KEY,
    env_id                varchar(26)   NOT NULL,
    data_id               varchar(128)  NOT NULL,
    nacos_history_id      varchar(128)  NOT NULL,      -- 原 tongncs_history_id
    detected_at           timestamptz   NOT NULL,
    match_release_id      varchar(26),                 -- 命中平台发布记录时填写
    verdict               varchar(16)   NOT NULL,      -- MATCHED / UNAUTHORIZED / BLIND_SPOT
    notified_at           timestamptz,
    closed_at             timestamptz,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_direct UNIQUE (env_id, data_id, nacos_history_id)  -- 同一变更不重复报警（R-AUD-06）
);

-- ============ 回收站 ============

CREATE TABLE ncg_recycle_bin (
    item_id          varchar(26)   PRIMARY KEY,
    mapping_id       varchar(26)   NOT NULL,
    deleted_at       timestamptz   NOT NULL,
    deleted_by       varchar(26)   NOT NULL,
    purge_at         timestamptz   NOT NULL,           -- deleted_at + 30 天（R-SYS-06）
    restore_status   varchar(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING / RESTORED / PURGED
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_recycle_mapping UNIQUE (mapping_id)
);

-- ============ 环境同步 ============

CREATE TABLE ncg_env_sync_snapshot (
    snapshot_id        varchar(26)   PRIMARY KEY,
    source_env         varchar(16)   NOT NULL,         -- dev / staging / prod
    target_env         varchar(16)   NOT NULL,
    config_count       int           NOT NULL,
    diff_summary       jsonb         NOT NULL,          -- 仅源存在/仅目标存在/内容不同
    created_by         varchar(26)   NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    expires_at         timestamptz   NOT NULL
);
CREATE INDEX idx_sync_created ON ncg_env_sync_snapshot (created_at);

-- ============ 扫描游标 / Worker 租约 / 会话 ============

CREATE TABLE ncg_scan_cursor (
    env_id                 varchar(26)   PRIMARY KEY,
    last_history_id        varchar(128)  NULL, -- NULL=初始游标尚无历史指针
    last_scan_at           timestamptz   NULL, -- NULL=尚未扫描过（代码回退 now-1h）,
    blind_spot_since       timestamptz,                 -- 历史缺口（R-AUD-07）
    consecutive_failures   int           NOT NULL DEFAULT 0
);

CREATE TABLE ncg_worker_lease (
    lease_key      varchar(64)   PRIMARY KEY,
    owner_id       varchar(128)  NOT NULL,
    expires_at     timestamptz   NOT NULL,
    heartbeat_at   timestamptz   NOT NULL,
    version        bigint        NOT NULL DEFAULT 0
);

-- Spring Session JDBC 框架表（spring.session.jdbc.table-name=ncg_session）
-- 列结构必须与 spring-session-jdbc 3.3.x schema-postgresql.sql 一致（PRIMARY_ID/SESSION_ID 两层主键、
-- 属性独立到 ncg_session_attributes，JOIN 条件 SA.SESSION_PRIMARY_ID = S.PRINCIPAL... 即 PRIMARY_ID）；
-- 详细设计 ch5 的 6 列简写是语义描述，物理表以框架契约为准（设计漂移备忘）。
CREATE TABLE ncg_session (
    PRIMARY_ID            char(36)      NOT NULL,
    SESSION_ID            char(36)      NOT NULL,
    CREATION_TIME         bigint        NOT NULL,
    LAST_ACCESS_TIME      bigint        NOT NULL,
    MAX_INACTIVE_INTERVAL int           NOT NULL,
    EXPIRY_TIME           bigint        NOT NULL,
    PRINCIPAL_NAME        varchar(100),
    CONSTRAINT ncg_session_pk PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX ncg_session_ix1 ON ncg_session (SESSION_ID);
CREATE INDEX ncg_session_ix2 ON ncg_session (EXPIRY_TIME);
CREATE INDEX ncg_session_ix3 ON ncg_session (PRINCIPAL_NAME);

CREATE TABLE ncg_session_attributes (
    SESSION_PRIMARY_ID char(36)     NOT NULL,
    ATTRIBUTE_NAME     varchar(200) NOT NULL,
    ATTRIBUTE_BYTES    bytea        NOT NULL,
    CONSTRAINT ncg_session_attributes_pk PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT ncg_session_attributes_fk FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES ncg_session (PRIMARY_ID) ON DELETE CASCADE
);

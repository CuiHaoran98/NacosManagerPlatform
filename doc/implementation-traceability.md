# NCG 实现追踪矩阵（FR → 规则 → 约束 → AC → 代码）

> 依据：`.trae/specs/formalize-governance-rules-and-acceptance/spec.md` 附录 D 追踪矩阵，
> 增列「实现落点」与「验证状态」两列，作为实现级证据（spec 附录 C 判定规则的静态部分）。
> 生成：2026-11-22（goal round 6），验证基线 `mvn test` 34 用例（33 绿 + 1 集成跳过，无 Docker）。
> 状态口径：`单测`=有自动化测试；`静态`=代码+审计比对，待运行时验收；`待 AC`=需运行环境按 spec 附录 C 出证据。

| PRD | 规则 / 约束 | 实现落点（src/main/java/com/nacos/ncg/…） | 验证状态 |
|---|---|---|---|
| **FR-01** 配置目录与映射 | R-CAT-01~04、C-ARC-02/18、AC-01 | catalog/domain/ConfigMapping（uk_mapping_ref）；catalog/app/CatalogService（resolve/getMapping/listMappings/listUnmanaged/createMapping→CAT_409-001/updateMapping/importMapping→CAT_409-002）；web/CatalogController、MappingAdminController | 静态（映射唯一=uk 索引兜底）；AC-01 待 AC |
| **FR-02** 变更单与基线 | R-CR-01~07、C-ARC-02/06、AC-02~04 | change/domain/ChangeRequest；change/app/ChangeDraftService（open：原因≥10→CR_400-001、内容≤1MB→CR_413-001、加密存储；submit：冻结 baselineMd5/impactSnapshot/riskLevel→PENDING_APPROVAL、活动单唯一→CR_409-001、风险只上调）；web/ChangeController | 单测=集成 happy-path 断言 baselineMd5 冻结（R-CR-04）；AC-02~04 待 AC |
| **FR-03** 审批与职责分离 | R-APR-01~05、R-AUTH-06~08、C-ARC-09、AC-05~07 | shared/PermissionPolicy（canApprove/canPublish）；approval/app/ApprovalService.decide（DENY_SAME_ACTOR\|DENY_SEPARATION→AUTHZ-403-002、高风险非负责人→AUTHZ-403-003、REJECT 理由≥10→APR-422-001、仅 PENDING_APPROVAL→APR-409-001）；web/ApprovalController+ApprovalActionController | 单测=PermissionPolicyTest 5；AC-05~07 待 AC |
| **FR-04** 发布、灰度与读回 | R-REL-01~07、C-ARC-03~05/20、AC-08~10 | release/app/ReleaseService（三事务；预检六项含 checkReleaseWindow→REL-422-001；幂等 releaseKey→REL-409-002=200；读回 MD5 一致才 VERIFIED；indeterminate→REL-502-001 禁自动重试；detectSubscriberDiff→R-REL-07；recordGrayPhase→R-REL-03 放量/转全量留痕）；RollbackService.publishVersion 同模式；adapter/nacos/port.publish 三态；web/ReleaseController | 单测=FakeNacosConfigAdapterTest 10 契约+ReleaseWindowTest 4；AC-08~10 待 AC |
| **FR-05** 审计与直连检测 | R-AUD-01~07、C-ARC-06/11、AC-11~14 | audit/app/AuditWriter（MANDATORY 同事务+按日哈希链+advisory lock）；V1__init.sql trg_audit_event_no_update（append-only 触发器）；AuditChainService.verify（逐条重算）；AuditExportService（CSV+尾注 sha256≤10 万条）；audit/app/DirectScanService（matchRelease 5 分钟窗、UNAUTHORIZED 告警、BLIND_SPOT 不推断、uk_direct 防重）；worker.runAuditChainVerify | 单测=HashChainTest 4+集成断言链完整；AC-11~14 待 AC |
| **FR-06** 身份、授权与账号 | R-AUTH-01~10、C-ARC-10/12、AC-18/26 | auth/app/AccountService（Argon2id、密码≥12、锁 5 次 15 分钟）；GrantService（10 权限点、expires_at 到期即失效）；NcgUserDetailsService；web/SecurityConfig（表单登录+CSRF+fail-closed 401+会话 30 分钟）；MappingAdminController.sensitive-view（VIEW_SENSITIVE+原因≥10+CONFIG_SENSITIVE_VIEW 审计+no-store）；BootstrapAdmin | 静态；AC-18（敏感查看留痕）/AC-26 待 AC |
| **FR-07** 影响面与风险 | R-IMP-01~05、C-ARC-13/14、AC-06/17 | shared/RiskCalculator（7 因子、订阅未知→上调、只上调）；ChangeDraftService.submit 冻结 impactSnapshot；ReleaseService.detectSubscriberDiff；ChangeController GET /changes/{id}/impact（缺失→IMP-409-001） | 单测=RiskCalculatorTest 6；AC-06/17 待 AC |
| **FR-08** 回滚、软删除与恢复 | R-RB-01~05、R-SYS-06、C-ARC-19、AC-15/16 | release/app/RollbackService（rollback：上一版直接 EXECUTED/更早 DRAFT_REQUIRED；restore：30 天外→RB-410-001、同名冲突→RB_409-001、写回+读回；softDelete：purge_at=+30 天）；catalog/domain/RecycleBinItem；worker.runRecycleBinCleanup（每日清过期）；web/RollbackController、RecycleBinController | 静态；AC-15/16 待 AC |
| **FR-09** 通知与集成 | R-NTF-01~05、C-ARC-07/12、AC-19 | notification/app/NotificationOutbox（业务同事务入队、dedupKey 5 分钟聚合）；NotificationDispatcher（退避 1/5/15、3 次→DEAD+审计）；WecomSender；NotifyBindingService（target 密文）；worker.runNotificationDispatch | 静态；AC-19 待 AC |
| **FR-10** 多环境连接与映射 | R-CON-01~05、C-ARC-17/18、AC-23/24 | catalog/app/InstanceService（register、recordProbe 3 次失败→UNAVAILABLE）；adapter/nacos/config/NacosConnectionRegistry+port.test；worker.runInstanceProbe（60s）；web/CatalogController /instances[/{envId}/test] | 静态；AC-23/24 待 AC |
| **FR-11** 跨环境对比与受控同步 | R-SYN-01~05、C-ARC-03/09、AC-20 | envsync/app/EnvSyncService（compare 只读两侧 md5；propose 只建目标变更单 SYN_409-001，绝不直写目标=唯一写入口）；web/EnvSyncController | 静态；AC-20 待 AC |
| **FR-12** 定时发布与批量变更 | R-SCH-01~06、C-ARC-08/20、AC-21/22 | scheduler/app/ScheduleService（schedule/reschedule 只改 execute_at 不重审批/executeDue 六项复核失败 RETURNED 不补发）；batch/app/BatchService（上限 50→BAT-413-001、成功子项永不重发、PARTIAL_SUCCESS 只重试失败项）；worker/app/WorkerTasks+LeaseManager（租约 30s 单活）；web/ScheduleController、BatchController | 静态；AC-21/22 待 AC |
| **横切** 一致性/降级 | R-SYS-01~10、C-ARC-01~20、AC-23/25 | shared/NcgErrorCode 37 码+ApiError/ApiExceptionHandler（{code,message,traceId,details,timestamp}）+TraceIdFilter；shared/crypto/CryptoService（AES-256-GCM enc:v1 fail-closed）；SYS-423-001/REL-503-001 只读降级；shared/Ulid、json/Jsonb 转换器 | 静态；AC-23/25 待 AC |
| **架构** 模块边界与可测试性 | R-SYS-01~03、C-ARC-01~20、AC-08/09/11/12 | 12 模块 api/app/domain/repo 分层；唯一写入口 grep 证据=port.publish 仅 ReleaseService.java:203 与 RollbackService.java:268（均 release 模块发布编排）；Fake/Real 适配器可互换 | 静态（grep+结构） |
| **验收** 报告与追踪 | 本规格，AC 全部 | 本文档+doc/static-acceptance-report.md+todo `t-1051b911`（运行时验收） | 待 AC（无 Docker，留 CI） |
| **文档** 治理文档与执行约束 | R-SYS-01~03、R-REL-02、R-REL-06、C-ARC-03~06、G-16 | agent.md（项目上下文）+rule.md（行为红线）v1.3 已交付；G-16 改判随本文档提交 | 静态（G-16 待用户改判） |

## 红线证据（rule.md 对应）

| 红线 | 证据 |
|---|---|
| 只有 Release 发布编排可写 Nacos（C-ARC-03） | `grep port.publish` 全仓仅 ReleaseService.java:203、RollbackService.java:268 |
| 状态与审计同事务（C-ARC-06） | AuditWriter.append `@Transactional(propagation=MANDATORY)`，无事务调用即抛 |
| indeterminate 禁自动重试（C-ARC-05） | ReleaseService/RollbackService 只记录+通知，人工 reconcile 才推进 |
| 审计 append-only（C-ARC-11） | DB 触发器拒 UPDATE/DELETE + 按日哈希链 + AuditChainService 逐条重算 |
| 权限失败即拒绝（fail-closed） | ActorResolver 无会话→AUTH_401-001；SecurityConfig anyRequest().authenticated() |
| 敏感值默认脱敏、明文查看需原因+审计 | sensitive-view 端点：VIEW_SENSITIVE+原因≥10 字+CONFIG_SENSITIVE_VIEW+no-store |

## 未决项映射（[Assumption, TBD]）

| 项 | 值 | 代码位置 |
|---|---|---|
| A-01 高风险阈值 | diffLineThreshold=5000、instanceCountThreshold=30（可构造注入） | shared/RiskCalculator |
| A-02 内容上限 | NCG_CONTENT_MAX_BYTES 默认 1MB | ChangeDraftService |
| A-03 批量上限 | NCG_BATCH_MAX_ITEMS 默认 50 | BatchService |
| A-04 灰度观察窗口 | NCG_GRAY_WINDOW_MINUTES 默认 30 | application.yml（receipt 记录） |
| A-05 敏感识别 | 映射 sensitive 标志人工登记 | ConfigMapping |
| A-06 代理人 | P0 不放行（高风险仅负责人） | PermissionPolicy |
| A-07 审计保留 | 在线 13 个月 TBD，永久保留哈希链 | — |
| A-08 密码策略 | ≥12 位/90 天 TBD/锁 5 次 15 分钟 | AccountService |

---

## 运行时证据补录（2026-11-22，真 PG15 + fake 适配器 HTTP 级冒烟）

- 核心链：建单→提交（风险 off-hours 自动上调）→审批（职责分离）→发布→读回 VERIFIED（expected==readback）→审计链 `ok:true`（62+ 事件）→幂等重放返既有记录（REL-409-002=200）。
- 失败路径：INDETERMINATE→reconcile（发布记录不改历史）；回滚 EXECUTED/DRAFT_REQUIRED 双模式；软删→回收站 purge_at+30 天→恢复（RESTORE 发布 VERIFIED）；定时发布（SCHEDULED/worker-scheduler）；批量（2/2 SUCCEEDED、BATCH 发布记录）；envsync compare+propose；敏感查看（原因门槛+no-store+审计留原因）。
- 冒烟期修复 11 个缺陷（详见 doc/acceptance-report.md §3），全部回归 34/34 测试绿。
- 验收对照：doc/acceptance-report.md（AC-01~26，12 PASS / 12 PASS(部分) / 2 BLOCKED——直连检测需进程内注入，留集成测试）。

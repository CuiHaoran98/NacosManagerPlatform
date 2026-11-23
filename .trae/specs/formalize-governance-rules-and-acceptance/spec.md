# TongNCS 配置治理规则、约束与验收规格

## Why

PRD v1.1 已定义 12 项 P0 功能与产品边界，架构稿已定义模块、深接口与部署形态，但业务规则、架构约束与验收标准仍分散在文档、演示和口头决策中。开发、测试、评审缺少唯一可执行的基线，容易出现“功能做了但规则不一致”“架构写了但代码越界”“验收靠印象”三类问题。本规格把三者收敛为可编号、可追踪、可验收的契约。

## What Changes

- 新增业务规则目录（R-xx），覆盖身份授权、配置目录、变更单、审批、发布、审计、影响面、回滚恢复、通知、连接、跨环境同步、定时批量与横切规则。
- 新增架构约束目录（C-xx），覆盖模块边界、唯一写入口、TongNCS 适配器、审计事务、发件箱、租约、数据所有权与测试面。
- 新增验收场景（AC-xx）、静态验收评估报告与《验收报告》模板，定义证据、判定与签收条件。
- 新增字段级规则、状态机、错误码与接口级约束，把“规则”细化到可实现的粒度。
- 新增 PRD → 规则 → 约束 → 验收 的追踪矩阵，校验覆盖率与孤儿项。
- 新增附录 E：核心对象字段表、状态机、错误码表、接口级事务/幂等约束与并发唯一约束。
- 新增附录 F：默认参数表（阈值、超时、保留期、密码策略等），未知值一律显式标注。
- 新增附录 G：静态验收评估报告（文档级），给出 12 PASS / 4 BLOCKED / 0 FAIL 的结论与缺口动作。
- 新增治理文档交付要求：`agent.md`（项目上下文说明书）与 `rule.md`（MUST / MUST NOT 行为红线），与规格同版本演进；完整规则目录保留在附录 A，不在两份文档中重复。
- 不新增 P0 功能，不改变 PRD 的范围与决策；仅把既有决策固化为可验证条款。
- **BREAKING**：无。本规格是新增的治理基线，不删除或替换现有 PRD/架构文档。

## Impact

- Affected specs：配置目录、变更单、审批、发布与灰度、审计与直连检测、身份授权、影响面、回滚与恢复、通知、连接与映射、跨环境同步、定时与批量、降级与一致性、架构约束、字段/状态/错误码/接口约束、治理文档（agent.md / rule.md）、验收与追踪。
- Affected code：后续实现的 Catalog、ChangeDraft、Approval、Release、Impact、EnvSync、Scheduler、AuditTrail、DirectScan、AuthZ、Notify、TongNCS Adapter，以及 Web/Worker/元数据库部署。本阶段只产出规格，不写代码。

## ADDED Requirements

### Requirement: 配置目录与映射（FR-01）

系统必须提供“应用 → 环境 → 配置分组 → 配置项”的统一目录，并把每个配置稳定映射到唯一的 TongNCS 坐标；目录不是配置事实源。

#### Scenario: 映射冲突被阻断

- **WHEN** 管理员把同一 TongNCS 配置映射到第二个应用或环境
- **THEN** 系统阻断保存，提示已归属的应用/环境与操作人，并写入审计

#### Scenario: TongNCS 不可用时只读降级

- **WHEN** 某个环境实例不可达
- **THEN** 目录展示最后一次快照、采集时间与“可能过期”，不伪装成实时数据，且禁止该环境的写操作

### Requirement: 变更单与基线（FR-02）

系统必须把每一次配置修改收敛为变更单，草稿只保存在平台；提交时冻结基线，发布前重新校验基线。

#### Scenario: 草稿不影响运行时

- **WHEN** 编辑者保存或反复修改草稿但未发布
- **THEN** TongNCS 中的配置内容保持不变，运行时订阅者不受影响

#### Scenario: 基线漂移退回

- **WHEN** 审批通过后、发布前，TongNCS 配置被其他人修改
- **THEN** 发布被阻断，变更单退回“待审批”，展示基线、草稿、当前值三方 Diff

### Requirement: 审批与职责分离（FR-03）

系统必须执行生产环境的职责分离与单人会签：提交人不得审批，发布人不得审批（应用负责人例外），高风险变更只能由应用负责人审批。

#### Scenario: 提交人不能审批自己的变更

- **WHEN** 提交人打开自己提交的变更单审批页
- **THEN** 系统不提供通过/驳回动作，并说明职责分离规则

#### Scenario: 高风险变更指定审批人

- **WHEN** 变更被判定为高风险
- **THEN** 只有该应用负责人（或其已审计的代理人）可以审批，其他审批人不可见通过动作

### Requirement: 发布、灰度与读回（FR-04）

系统必须通过唯一写入口发布配置；写入后立即读回并比对 MD5，只有读回一致才算成功；不确定结果不得自动重试。

#### Scenario: 读回一致才算成功

- **WHEN** 发布人执行发布
- **THEN** 系统写入 TongNCS、立即读回并比对 MD5；一致才记录“发布成功”，不一致记录失败并告警

#### Scenario: 结果不确定禁止自动重试

- **WHEN** 写入请求已发出但读回超时或无法确认
- **THEN** 发布结果为 `indeterminate`，系统禁止自动重试，进入人工对账并高优告警

### Requirement: 审计与直连检测（FR-05）

系统必须提供只追加、不可篡改、永久保留的审计；对绕过平台直接修改 TongNCS 的行为必须在 5 分钟内发现并报警。

#### Scenario: 审计失败阻断业务

- **WHEN** 审计写入失败
- **THEN** 当前业务操作失败，不提交状态变更；外部写入的结果审计失败时标记“待对账”，不报告成功

#### Scenario: 直连修改被报警

- **WHEN** 有人在 TongNCS 控制台直接修改已纳管配置
- **THEN** 检测任务在 5 分钟内生成“未授权变更”审计事件，并通过企业微信群与站内通知 @ 应用负责人

### Requirement: 身份、授权与账号（FR-06）

P0 必须使用平台自建账号与“应用 × 环境”授权；权限变更即时生效，判断失败时拒绝；敏感值明文默认只授予审批人与应用负责人。

#### Scenario: 禁用账号立即失效

- **WHEN** 管理员禁用某个账号
- **THEN** 该账号立即无法登录与执行写操作，其名下未完成变更单转交应用负责人

#### Scenario: 敏感值查看留痕

- **WHEN** 非默认授权用户申请查看敏感值明文
- **THEN** 系统要求填写原因，校验单独权限，展示后写入审计（含原因与对象）

### Requirement: 影响面与风险（FR-07）

系统必须把订阅实例稳定映射到应用与负责人，计算低/中/高风险；影响面与风险是审批和发布的前置证据。

#### Scenario: 影响面定位到负责人

- **WHEN** 编辑者提交变更
- **THEN** 系统展示订阅实例数、涉及应用与负责人；映射缺失时标注“未知”并阻断高风险发布

#### Scenario: 监听不可用上调风险

- **WHEN** TongNCS 监听查询不可用
- **THEN** 影响面显示“未知”，风险等级自动上调一级，并在审批页明示证据缺口

### Requirement: 回滚、软删除与恢复（FR-08）

系统必须支持回滚到任一发布版本或 TongNCS 历史版本；删除为软删除，保留 30 天可恢复；恢复与回滚都必须写 TongNCS 并读回。

#### Scenario: 上一版本直接回滚

- **WHEN** 发布者选择“回滚到上一已发布版本”并填写原因
- **THEN** 系统直接执行回滚、读回校验并生成新发布记录，事后审计，不覆盖历史

#### Scenario: 30 天内恢复与同名冲突

- **WHEN** 发布者在删除后 30 天内恢复配置
- **THEN** 若同名配置不存在则直接恢复；若已存在则要求选择“覆盖并重新审批”或放弃，禁止静默覆盖

### Requirement: 通知与集成（FR-09）

P0 必须提供站内通知与企业微信群机器人；通知失败不得阻塞业务，且不得包含敏感明文。

#### Scenario: 企微失败降级站内

- **WHEN** 企业微信机器人连续失败
- **THEN** 站内通知仍然送达，系统重试 3 次后记录失败审计并通知平台管理员，不回滚业务

#### Scenario: 通知不含明文

- **WHEN** 发送任何通知
- **THEN** 内容只含对象、动作、结果、操作人与链接，敏感值保持脱敏

### Requirement: 多环境连接与映射（FR-10）

系统必须把 dev/staging/prod 绑定到各自独立的 TongNCS 实例；连接测试必须验证连通性与读写权限；实例不健康时阻断发布。

#### Scenario: 环境与实例一一绑定

- **WHEN** 管理员配置环境
- **THEN** 每个环境只能绑定一个实例，同一实例不得复用于其他环境，冲突阻断保存

#### Scenario: 不健康实例阻断发布

- **WHEN** 某环境实例连续探活失败
- **THEN** 该环境标记“不可用”，发布、回滚、恢复与灰度全部阻断，恢复后不自动补发

### Requirement: 跨环境对比与受控同步（FR-11）

系统必须支持 dev → staging → prod 的只读对比与受控同步；同步只能生成目标环境变更单，不得直接写目标环境。

#### Scenario: 同步走目标审批

- **WHEN** 用户选择把 dev 的配置同步到 staging
- **THEN** 系统只生成 staging 的变更单，必须经过 staging 审批与发布，dev 内容不被修改

#### Scenario: 目标有进行中变更单

- **WHEN** 目标环境同一配置已存在进行中变更单
- **THEN** 同步被阻断，提示已有单号，不创建第二张单

### Requirement: 定时发布与批量变更（FR-12）

系统必须支持审批通过后的定时发布与父单+子项的批量变更；到点必须重新校验，失败不自动补发；批量允许部分成功且只重试失败项。

#### Scenario: 到点重检失败

- **WHEN** 定时任务到点，但基线已变化或实例不可用
- **THEN** 任务转回“待发布”并告警，不执行写入，不自动重试

#### Scenario: 批量部分成功

- **WHEN** 批量变更中部分子项发布失败
- **THEN** 成功的子项不重发，失败子项可单独重试，父单展示部分成功与失败明细

### Requirement: 降级、失败与一致性

系统必须在 TongNCS 不可用、审计失败、权限判断失败、通知失败、Worker 崩溃等条件下保持可解释的行为，不允许静默成功或静默失败。

#### Scenario: TongNCS 不可用进入只读

- **WHEN** 所选环境实例不可达
- **THEN** 平台允许查看与编辑草稿，禁止发布/回滚/恢复/灰度；恢复后不自动执行任何写操作

#### Scenario: 重复提交幂等

- **WHEN** 发布人重复点击发布或 Worker 重放同一任务
- **THEN** 系统按“变更单号 + 目标版本”返回既有发布记录，不产生第二次写入

### Requirement: 架构约束与可测试性

实现必须遵守模块化单体、唯一写入口、TongNCS 适配器接缝、状态与审计同事务、通知发件箱、数据库租约与幂等键等约束；测试必须跨接口进行。

#### Scenario: 唯一写入口

- **WHEN** 任何模块需要修改 TongNCS 配置
- **THEN** 只能调用发布编排的发布/回滚/恢复接口，不得直接调用适配器写方法或 TongNCS API

#### Scenario: 适配器契约测试双跑

- **WHEN** 运行适配器契约测试
- **THEN** 同一套测试同时跑测试假件与真实 TongNCS 沙箱，调用方测试不绕过接口

### Requirement: 治理文档与上下文（agent.md / rule.md）

实现与协作必须由两份职责分离的版本化文档支撑：`agent.md` 是面向 AI 代理与实现者的项目上下文说明书，只说明项目定位、目录地图、阅读顺序、核心概念与工作方式；`rule.md` 是行为红线清单，只回答“必须做什么、绝不能做什么”。完整规则目录保留在本规格附录 A，字段、状态机与错误码保留在附录 E，两份文档不重复复制。

#### Scenario: rule.md 可执行且可追溯

- **WHEN** 校验 `rule.md`
- **THEN** 每条 MUST / MUST-NOT 都引用真实存在的 R-xx 或 C-ARC-xx，并给出对应 AC-xx；语义与附录 A 不冲突，不新增、不改写规则

#### Scenario: agent.md 提供项目上下文

- **WHEN** 新的实现者或 AI 代理进入本仓库
- **THEN** 能按 `agent.md` 找到 PRD、架构稿、spec、rule.md 的位置与阅读顺序，了解当前阶段、核心概念与工作方式，且文档中不重复 rule.md 的规则清单

#### Scenario: 文档漂移被发现

- **WHEN** 规则编号、计数或 FR 覆盖与规格不一致
- **THEN** 一致性校验失败，文档不得标记为已冻结

### Requirement: 验收报告与追踪

系统交付必须附带验收报告，报告中的每个结论必须有证据；验收场景、规则与 PRD 功能必须可追踪。

#### Scenario: 缺证据不得签收

- **WHEN** 某个 P0 验收场景没有审计事件、发布记录、读回 MD5、TraceId 或截图等证据
- **THEN** 该场景判定为 BLOCKED，报告不得签收

#### Scenario: 追踪矩阵无孤儿

- **WHEN** 检查追踪矩阵
- **THEN** 每个 FR 至少映射 1 条规则与 1 个验收场景，每个 AC 至少关联 1 条规则；规则按域归属到 FR 或横切域，不要求逐条独立 AC

## MODIFIED Requirements

无。本规格不修改既有 PRD 功能定义，只新增可验证的规则、约束与验收基线。

## REMOVED Requirements

无。

## 附录 A：业务规则目录

> 本目录规则均服务于 P0；规则按域归属到对应 FR，关键路径由附录 C 的 AC 覆盖，其余由附录 B 的约束检查或代码评审覆盖。

### A.1 身份与授权（AUTH）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-AUTH-01 | P0 采用平台自建账号；仅平台管理员可创建、禁用、冻结账号；不提供自助注册 | FR-06 |
| R-AUTH-02 | 权限点固定枚举：查看、编辑、提交、审批、发布、回滚、查看敏感值、查看审计、导出审计、平台管理；新增需版本化 | FR-06 |
| R-AUTH-03 | 授权范围为“应用 × 环境”；支持授予用户、用户组、角色模板；不授予 TongNCS 全局权限 | FR-06 |
| R-AUTH-04 | 一次操作必须同时满足平台权限与 TongNCS 服务账号权限；平台不以个人账号调用 TongNCS | FR-06、FR-10 |
| R-AUTH-05 | 权限变更即时生效；权限判断失败时拒绝（fail-closed）；回收权限不改变已发布配置 | FR-06 |
| R-AUTH-06 | 生产环境：提交人不得审批；发布人不得审批；例外：发布人是该应用负责人 | FR-03、FR-06 |
| R-AUTH-07 | 高风险变更只能由应用负责人审批；负责人不可用时由管理员指定代理人，代理关系与审批动作均写审计 | FR-03、FR-07 |
| R-AUTH-08 | 生产审批为单人会签：1 名合格审批人通过即可；不设审批超时、不自动驳回 | FR-03 |
| R-AUTH-09 | 敏感值明文默认授予审批人与应用负责人；其他角色查看需单独授权、填写原因并写审计 | FR-05、FR-06 |
| R-AUTH-10 | 账号禁用或冻结后立即阻断登录与写操作；其名下未完成变更单转交应用负责人 | FR-06 |

### A.2 配置目录（CAT）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-CAT-01 | 目录层级固定为应用 → 环境 → 配置分组 → 配置项；一个 TongNCS 配置只能归属一个应用与环境；冲突阻断保存 | FR-01 |
| R-CAT-02 | 未纳管配置进入清单，按数量与存在时长提示；纳管动作写审计 | FR-01 |
| R-CAT-03 | 目录详情实时读取 TongNCS；不可用时展示最后快照、采集时间与“可能过期”，不展示为实时 | FR-01 |
| R-CAT-04 | 敏感值默认脱敏；复制、导出、通知均不得出现明文 | FR-01、FR-05 |

### A.3 变更单（CR）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-CR-01 | 变更单必填：标题、变更原因、目标配置、变更类型、变更内容（结构化格式为按配置项的新值 `items[]`；`CREATE` / `TEXT` / 整文件替换为 `content`）、计划发布时间；风险由系统计算，用户只能上调 | FR-02 |
| R-CR-02 | 草稿只保存在平台，不写入 TongNCS，不影响运行时 | FR-02 |
| R-CR-03 | 同一配置同时只允许一个“进行中”变更单；草稿不占用名额；冲突时阻断并给出已有单号 | FR-02 |
| R-CR-04 | 提交时冻结基线 MD5 与影响面快照；后续 TongNCS 变化不回写已提交快照 | FR-02 |
| R-CR-05 | 发布前必须重新读取基线；不一致时退回“待审批”，展示基线、草稿、当前值三方 Diff | FR-02、FR-04 |
| R-CR-06 | 删除类变更必须单独确认；删除为软删除，内容保留 30 天；超期不可恢复 | FR-02、FR-08 |
| R-CR-07 | 单个配置内容上限默认 1 MB [Assumption, TBD]；格式校验失败需定位到行并阻断提交 | FR-02 |
| R-CR-08 | 平台按 DataId 后缀识别配置格式：`.properties` → PROPERTIES，`.yaml` / `.yml` → YAML，`.json` → JSON，其余 → TEXT；格式在纳管时识别并固化，纳管后不可变更。所有格式统一解析为“配置项 key + 值”，编辑单位是配置项，发布单位仍是整个 DataId 文件 | FR-02 |
| R-CR-09 | properties 按 `key=value` / `key:value` 行解析，`#` / `!` 开头为注释；YAML 按层级解析并将嵌套 key 展开为点路径、数组下标写作 `[n]`；解析失败必须返回行号（可得的列号）并阻断提交。后缀无法识别或格式不支持时，该 DataId 只允许只读浏览，禁止发起变更 | FR-02 |

### A.4 审批（APR）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-APR-01 | 审批动作仅三种：通过、驳回（必填理由）、转交（需审批权限且转交对象合格） | FR-03 |
| R-APR-02 | 审批页必须展示完整 Diff、影响面、历史发布/回滚、提交人、风险等级 | FR-03 |
| R-APR-03 | 审批通过后持续有效，直到基线变化、变更单取消或发布完成；不设超时 | FR-03 |
| R-APR-04 | 审批期间基线变化时，变更单退回“待审批”；已有点评保留，但必须重新确认 | FR-03 |
| R-APR-05 | 无合格审批人时升级至应用负责人并告警；审批人离职或禁用时可批量转交 | FR-03 |

### A.5 发布（REL）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-REL-01 | 发布前预检六项：变更单状态、基线一致、发布窗口、TongNCS 可用、服务账号权限、发布人权限 | FR-04 |
| R-REL-02 | 写入后立即读回并比对 MD5；不一致视为发布失败，不得报告成功 | FR-04 |
| R-REL-03 | 灰度复用 TongNCS Beta/Tag 能力；必须记录灰度方式、条件、观察窗口、放量/转全量结果 | FR-04 |
| R-REL-04 | 发布幂等键为“变更单号 + 目标版本”；重复点击返回既有发布记录，不产生第二次写入 | FR-04 |
| R-REL-05 | 发布窗口按环境配置，默认全天；窗口外阻断发布；P0 不提供紧急发布通道 | FR-04 |
| R-REL-06 | 发布结果三态：verified、rejected、indeterminate；indeterminate 禁止自动重试，进入人工对账 | FR-04 |
| R-REL-07 | 部分订阅实例未收到推送时，展示订阅差异并告警；不得静默忽略 | FR-04 |

### A.6 审计（AUD）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-AUD-01 | 审计范围：变更单全生命周期、删除/恢复、权限与账号、登录、敏感值查看、审计导出、连接与映射变更、直连检测 | FR-05 |
| R-AUD-02 | 每条事件固定包含：事件 ID、时间、操作人、来源 IP、动作、对象、变更前值、变更后值（脱敏）、结果、TraceId、关联变更单、哈希 | FR-05 |
| R-AUD-03 | 审计只追加，不可编辑或删除；按日生成哈希链；导出文件附校验值 | FR-05 |
| R-AUD-04 | 审计永久保留，不自动删除；仅提供查询与导出 | FR-05 |
| R-AUD-05 | 审计写入失败时业务失败；不允许“无审计的成功”；外部写入结果审计失败时标记待对账 | FR-05 |
| R-AUD-06 | 直连检测每 5 分钟执行；先匹配平台发布记录，未匹配的记为“未授权变更”；通过企业微信群与站内通知并 @ 应用负责人 | FR-05、FR-09 |
| R-AUD-07 | TongNCS 历史不足或检测中断时标注“检测盲区”，不得推断为“无变更” | FR-05 |

### A.7 影响面（IMP）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-IMP-01 | 影响面必须把订阅实例稳定映射到应用与负责人；映射缺失时标注“未知”并阻断高风险发布 | FR-07 |
| R-IMP-02 | 风险因子至少包含：环境、敏感级别、变更类型、Diff 规模、订阅实例数、近 7 天失败/回滚 [Assumption, TBD]、执行时间 | FR-07 |
| R-IMP-03 | 风险等级为低/中/高；用户只能上调；高风险必须由应用负责人审批 | FR-07 |
| R-IMP-04 | 影响面必须展示“失败会怎样”与“回滚路径是否可用” | FR-07 |
| R-IMP-05 | 监听查询不可用时显示“未知”并将风险上调一级；无订阅时提示人工确认 | FR-07 |

### A.8 回滚与恢复（RB）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-RB-01 | 回滚目标可以是平台发布记录中的任意版本，或 TongNCS 历史版本 | FR-08 |
| R-RB-02 | 回滚到上一已发布版本：发布者可直执行，填写原因，事后审计；回滚到更早版本：必须创建回滚变更单并审批 | FR-08 |
| R-RB-03 | 回滚与恢复同样写入 TongNCS 并读回；生成新的发布记录，不覆盖历史 | FR-08 |
| R-RB-04 | 恢复需要发布权限；同名冲突时必须选择“覆盖并重新审批”或放弃，禁止静默覆盖 | FR-08 |
| R-RB-05 | 回滚或恢复失败时保持原状态，并高优告警，不得静默失败 | FR-08 |

### A.9 通知（NTF）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-NTF-01 | P0 通知渠道为站内通知与企业微信群机器人；其他渠道列 P1 | FR-09 |
| R-NTF-02 | 按应用与环境绑定企业微信群；直连变更、发布失败、定时失败必须 @ 应用负责人 | FR-09 |
| R-NTF-03 | 通知内容不得包含敏感明文；必须包含对象、动作、结果、操作人、跳转链接 | FR-09 |
| R-NTF-04 | 通知失败重试 3 次；最终失败写审计但不阻塞业务 | FR-09 |
| R-NTF-05 | 同一对象短时间内的同类事件聚合发送，避免通知风暴 | FR-09 |

### A.10 多环境连接（CON）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-CON-01 | 环境与 TongNCS 实例一一绑定；dev/staging/prod 使用独立实例；不允许跨环境复用实例 | FR-10 |
| R-CON-02 | 实例凭证加密存储；界面不回显；只允许覆盖更新，不提供查看原文 | FR-10 |
| R-CON-03 | 连接测试读取配置列表与样例配置，验证连通性与读写权限；失败给出可操作原因 | FR-10 |
| R-CON-04 | 映射支持批量导入；导入前检测冲突；一个配置只能映射到一个应用与环境 | FR-10 |
| R-CON-05 | 平台定时探活；连续失败标记实例不可用并阻断发布；恢复后不自动补发 | FR-10 |

### A.11 跨环境同步（SYN）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-SYN-01 | 跨环境对比以逻辑配置为锚，只读三个环境的当前内容，不修改任何环境 | FR-11 |
| R-SYN-02 | 差异分类为：仅源存在、仅目标存在、内容不同；敏感值脱敏展示 | FR-11 |
| R-SYN-03 | 同步只能生成目标环境的变更单，必须走目标环境审批与发布，不得直接写目标 | FR-11 |
| R-SYN-04 | 目标环境存在进行中变更单、映射缺失或 `ConfigMapping.format` 不一致（如源为 PROPERTIES、目标为 YAML）时阻断同步 | FR-11 |
| R-SYN-05 | 对比快照只读可分享；必须记录源环境、源版本与关联变更单 | FR-11 |

### A.12 定时与批量（SCH）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-SCH-01 | 审批通过后可设置执行时间；执行前可取消或改期；改期不重新审批，修改内容必须重新审批 | FR-12 |
| R-SCH-02 | 到点后重新校验审批、基线、窗口、连接与权限；任一失败转回“待发布”并告警，不自动重试 | FR-12 |
| R-SCH-03 | 定时任务以系统账号代执行；审计同时记录系统账号与原发布人；发布记录归原发布人 | FR-12 |
| R-SCH-04 | 批量变更为父单加子项；每个子项独立 Diff 与影响面；按最高风险审批；高风险子项由应用负责人审批 | FR-12 |
| R-SCH-05 | 批量逐项发布，允许部分成功；失败子项可单独重试；已成功子项不得重发 | FR-12 |
| R-SCH-06 | 单次批量上限默认 50 项 [Assumption, TBD]；超限阻断 | FR-12 |

### A.13 横切规则（SYS）

| 编号 | 规则 | 来源 |
|---|---|---|
| R-SYS-01 | TongNCS 是配置内容唯一事实源；平台草稿、快照、Diff 不是运行时配置来源 | 全局 |
| R-SYS-02 | 任何“发布成功”必须已写入 TongNCS 并通过读回校验 | 全局 |
| R-SYS-03 | 状态变更与审计写入必须同事务；不允许手工修改状态 | 全局 |
| R-SYS-04 | 变更单、审批、发布、审计通过变更单号关联，形成可追溯链 | 全局 |
| R-SYS-05 | TongNCS 不可用时平台进入只读：可查看与编辑草稿，禁止发布/回滚/恢复/灰度；恢复后不自动执行写操作 | 全局 |
| R-SYS-06 | 配置删除为软删除，保留 30 天；超期清除并写审计 | 全局 |
| R-SYS-07 | 固定参数：审计永久保留、审批无超时、直连检测 5 分钟、发布窗口默认全天；灰度观察 30 分钟、批量 50 项与内容 1 MB 为默认值 [Assumption, TBD] | 全局 |
| R-SYS-08 | P0 为单租户，不提供多租户隔离 | 全局 |
| R-SYS-09 | P0 不提供紧急发布通道 | 全局 |
| R-SYS-10 | P0 不接入 SSO，使用自建账号；SSO 列入 P1 评估 | 全局 |

## 附录 B：架构约束目录

| 编号 | 约束 | 对应模块/接缝 | 违反后果 | 验证方式 |
|---|---|---|---|---|
| C-ARC-01 | 模块化单体：无状态 Web + 单活 Worker + 元数据库，不拆微服务 | 部署 | 分布式一致性成本上升 | 部署图与进程清单 |
| C-ARC-02 | 配置事实源与治理元数据分离；平台不把生效配置作为事实源 | Catalog、Release | 出现第二事实源 | 数据所有权表 |
| C-ARC-03 | 唯一写入口为 Release；其他模块只读 TongNCS | Release、Catalog、EnvSync、DirectScan | 绕过审批与审计 | 依赖检查、代码评审 |
| C-ARC-04 | TongNCS 适配器是唯一 TongNCS 接缝；接口固定 5 个方法，不透出 CRUD | Adapter | 调用方各自处理认证/读回 | 接口评审、契约测试 |
| C-ARC-05 | `publish` 必须返回 verified/rejected/indeterminate；indeterminate 禁止自动重试 | Adapter、Release | 重复写入与状态错乱 | 契约测试、故障注入 |
| C-ARC-06 | 状态与审计同事务；外部写入采用“意图 + 结果”两段审计 | Release、AuditTrail | 无审计的成功 | 事务测试、审计查询 |
| C-ARC-07 | 通知走发件箱；通知失败不回滚业务 | Notify、Release | 业务被外部渠道拖垮 | 发件箱与重试测试 |
| C-ARC-08 | 定时/检测/批量使用数据库租约 + 幂等键；P0 不引入消息中间件 | Scheduler、DirectScan | 重复执行或任务丢失 | 租约与重放测试 |
| C-ARC-09 | 模块间只通过接口调用，不跨模块读表；依赖方向为界面 → 应用 → 适配器 | 全部模块 | 隐式耦合 | 依赖图、代码评审 |
| C-ARC-10 | 身份仅做本地账号适配器；不提前抽象 SSO 三层 | AuthZ | 过度设计 | 接口评审 |
| C-ARC-11 | 审计表 append-only；按日哈希链；禁止 UPDATE/DELETE | AuditTrail | 审计可被篡改 | 权限与属性测试 |
| C-ARC-12 | 凭证加密存储；日志、通知、导出不得出现明文 | 连接、通知、审计 | 敏感信息泄露 | 密钥检查、日志扫描 |
| C-ARC-13 | 外部调用必须设置超时、重试上限与熔断；错误映射为领域错误 | Adapter、Notify | 级联故障 | 故障注入 |
| C-ARC-14 | 所有请求带 TraceId，贯穿 Web → Worker → 适配器 | 全局 | 无法定位问题 | 日志与链路检查 |
| C-ARC-15 | 测试面等于接口；适配器契约测试同时跑假件与真实沙箱 | 全部模块 | 测试绕过边界 | 测试清单 |
| C-ARC-16 | 不引入服务网格、独立审计服务、通用工作流引擎、多租户 | 架构 | 运维与理解成本上升 | 架构评审 |
| C-ARC-17 | 部署：Web 2+、Worker 单活+热备、数据库主备、TongNCS 三套独立实例 | 部署 | 单点或串环境 | 部署核查 |
| C-ARC-18 | 数据所有权固定：TongNCS 配置、治理库过程与证据、快照派生、审计只增、回收站 30 天、通知状态 | 数据 | 责任不清 | 数据所有权表 |
| C-ARC-19 | 软删除保留 30 天；到期清除并写审计 | 回收站 | 误删不可恢复或数据滞留 | 时间旅行测试 |
| C-ARC-20 | 幂等键规范：发布=变更单号+目标版本；检测=环境+数据 ID+历史 ID | Release、DirectScan | 重复写入或重复报警 | 幂等测试 |

## 附录 C：验收场景与验收报告

### C.1 验收场景清单

| AC | 关联规则 | 场景 | 前置条件 | 执行步骤 | 期望结果 | 证据 |
|---|---|---|---|---|---|---|
| AC-01 | R-CAT-01、R-CON-04 | 同一 TongNCS 配置被映射到第二个应用/环境 | 已存在一条已纳管映射；具备平台管理权限 | 新建映射，选择另一应用/环境并填入相同 namespace/group/dataId 后保存 | 保存被阻断，提示已有归属 | 错误提示截图、映射记录 |
| AC-02 | R-CR-02 | 保存草稿但不发布 | 配置已纳管；编辑者具备编辑权限；记录当前 TongNCS 内容 | 新建变更单，保存草稿并多次修改，不提交发布；读取 TongNCS 与订阅端 | TongNCS 内容不变，订阅者无感知 | 草稿记录、TongNCS 读值 |
| AC-03 | R-CR-03 | 同一配置已有进行中变更单，再建一张 | 同一配置存在待审批/待发布/发布中的变更单 | 再次提交该配置的新变更单 | 新建被阻断并给出已有单号 | 变更单列表 |
| AC-04 | R-CR-05 | 审批后、发布前直接修改 TongNCS | 变更单已审批通过；记录基线 MD5 | 在 TongNCS 控制台修改该配置；回到平台点击发布 | 发布被阻断，退回待审批，展示三方 Diff | 发布记录、Diff 截图 |
| AC-05 | R-AUTH-06 | 提交人打开自己的变更单 | 用户 U 提交了变更单，且 U 具备审批权限点 | U 打开该变更单审批页 | 无通过/驳回动作 | 审批页截图 |
| AC-06 | R-AUTH-07、R-IMP-03 | 高风险变更由非应用负责人审批 | 变更被判定为高风险；审批人 P 非应用负责人及代理人 | P 打开审批页并尝试通过 | 审批动作不可用 | 审批页、风险记录 |
| AC-07 | R-AUTH-06 | 应用负责人作为发布人发布自己审批的变更 | 应用负责人 O 同时为发布人，且已审批该变更 | O 执行发布 | 允许发布并记录例外 | 发布记录、审计 |
| AC-08 | R-REL-02 | 发布后读回 MD5 一致 | 变更单已批准；实例可用；已计算期望内容 | 执行发布并等待读回 | 状态为成功，发布记录含读回值 | 发布记录、MD5 |
| AC-09 | R-REL-06 | 写入后读回超时 | 可注入读回超时或延迟 | 执行发布，使写入后读回超时 | 结果为 indeterminate，无自动重试 | 发布记录、告警 |
| AC-10 | R-REL-04 | 重复点击发布或重放同一任务 | 已有成功发布记录；release_key 已存在 | 再次点击发布；重放同一 Worker 任务 | 返回既有记录，无第二次写入 | 发布记录数、审计 |
| AC-11 | R-AUD-03 | 尝试修改或删除审计事件 | 已有审计事件；具备数据库或管理接口访问 | 尝试 UPDATE/DELETE 审计记录；运行哈希链校验 | 操作被拒绝；哈希链校验通过 | 权限错误、校验结果 |
| AC-12 | R-AUD-05 | 注入审计写入失败后执行发布 | 可注入审计写入失败 | 执行发布，使审计插入失败 | 业务失败，不产生“成功”记录 | 审计日志、发布状态 |
| AC-13 | R-AUD-06 | 在 TongNCS 控制台直接修改配置 | 配置已纳管；检测任务运行中；负责人已绑定企微群 | 在 TongNCS 控制台直接修改配置；等待不超过 5 分钟 | 5 分钟内产生未授权事件并报警 | 审计事件、企微截图 |
| AC-14 | R-AUD-07 | 制造历史查询缺口 | 可限制或清空 TongNCS 历史读取 | 触发检测任务并查看检测报告 | 标记“检测盲区”，不报告“无变更” | 检测报告 |
| AC-15 | R-SYS-06、R-RB-04 | 删除配置后第 29 天恢复 | 配置已删除；回收站保留 30 天；用时间旅行或调整 purge_at 模拟第 29 天 | 执行恢复并读取 TongNCS | 恢复成功并生成新发布记录 | 回收站、发布记录 |
| AC-16 | R-RB-04 | 恢复时同名配置已存在 | 回收站有已删除配置；同名配置已被创建 | 执行恢复并选择处理方式 | 要求覆盖并重新审批或放弃 | 冲突提示、审批记录 |
| AC-17 | R-IMP-01 | 订阅实例可映射到应用与负责人 | 存在订阅实例；映射已配置应用与负责人 | 提交变更并查看影响面 | 影响面展示应用、负责人、实例数 | 影响面截图 |
| AC-18 | R-AUTH-09 | 非默认用户查看敏感值 | 配置标记为敏感；用户无默认敏感值权限但已单独授权 | 点击查看明文，填写原因并确认 | 要求填写原因，成功后写审计 | 审计事件、查看记录 |
| AC-19 | R-NTF-04 | 企业微信机器人持续失败 | 可模拟企微机器人持续失败；站内通道正常 | 触发需通知的事件并观察 3 次重试 | 站内通知仍送达，记录失败审计 | 站内消息、审计 |
| AC-20 | R-SYN-03 | 从 dev 同步到 staging | 三环境实例可用；同一逻辑配置存在于 dev/staging；staging 无进行中单 | 执行对比并选择同步；查看 staging 变更单与三环境读值 | 只生成 staging 变更单，dev 不变 | 变更单、三环境读值 |
| AC-21 | R-SCH-02 | 定时到点时基线变化 | 变更已批准并设定执行时间；到点前修改 TongNCS | 等待到点并观察任务与变更单状态 | 转回待发布并告警，不写入 | 任务记录、告警 |
| AC-22 | R-SCH-05 | 批量中部分子项失败 | 批量父单含至少 2 个子项；可令其中 1 个失败 | 执行批量，并对失败子项重试 | 成功项不重发，失败项可单独重试 | 父单/子项记录 |
| AC-23 | R-SYS-05 | 断开某环境 TongNCS | 环境实例可用；平台已有快照 | 断网或停止实例；访问目录并尝试发布 | 只读、标注可能过期、禁止写 | 页面截图、接口响应 |
| AC-24 | R-CON-01 | 把同一实例绑定到两个环境 | 实例已绑定 dev 环境；具备平台管理权限 | 在 staging 选择同一实例并保存 | 保存被阻断 | 连接配置页 |
| AC-25 | R-SYS-09 | 尝试发起紧急发布 | 已登录；存在待发布变更单 | 查找紧急发布入口；直接调用相关接口 | 无入口，接口拒绝 | 页面、接口响应 |
| AC-26 | R-AUTH-05 | 回收用户发布权限后立即发布 | 用户 U 具备发布权限；变更单已批准 | 管理员回收 U 的发布权限；U 立即发布 | 发布被拒绝 | 权限错误、审计 |

### C.2 验收报告模板

```markdown
# TongNCS 配置治理平台验收报告

- 报告版本：
- 被测版本 / 构建号：
- 验收环境：dev / staging / prod（注明实例）
- 验收范围：AC-01 ~ AC-xx
- 执行人 / 日期：
- 关联 PRD：v1.1
- 关联架构稿：系统架构设计 v1.0

## 1. 结果汇总

| 指标 | 数值 |
|---|---|
| 用例总数 |  |
| PASS |  |
| FAIL |  |
| BLOCKED |  |
| P0 覆盖率 |  |

## 2. 明细

| AC | 场景 | 前置条件 | 执行步骤 | 期望 | 实际 | 证据 | 结论 |
|---|---|---|---|---|---|---|---|
| AC-xx |  |  |  |  |  | 审计 ID / 截图 / TraceId | PASS/FAIL/BLOCKED |

## 3. 缺陷

| 缺陷 ID | 级别 | 关联 AC | 描述 | 复现步骤 | 状态 |
|---|---|---|---|---|---|
|  | P0/P1/P2 |  |  |  |  |

## 4. 例外与风险

| AC | BLOCKED 原因 | 替代证据 | 批准人 | 后续动作 |
|---|---|---|---|---|
|  |  |  |  |  |

## 5. 签收

- 产品：
- 开发：
- 测试：
- 运维：
```

### C.3 判定与证据规则

- **PASS**：实际结果与期望一致，且证据可复核；P0 场景必须提供审计事件 ID、发布记录 ID、读回 MD5、TraceId 或截图中的至少两项。
- **FAIL**：实际结果与期望不一致，或出现未授权写入、无审计的成功、敏感明文泄露。
- **BLOCKED**：环境、依赖或数据不具备，无法执行；必须写明原因、替代证据与批准人。
- **签收条件**：全部 P0 验收场景 PASS；无未关闭的 P0/P1 缺陷；BLOCKED 已获批准且有替代证据；任何 FAIL 不得签收。
- **证据要求**：证据必须与被测版本对应；敏感值脱敏；审计导出附校验值；禁止用演示数据替代真实验收证据。
- **报告审计**：验收报告的生成与导出行为本身必须写审计。

## 附录 D：追踪矩阵

| PRD | 能力 | 规则 | 约束 | 验收 |
|---|---|---|---|---|
| FR-01 | 配置目录与映射 | R-CAT-01 ~ R-CAT-04 | C-ARC-02、C-ARC-18 | AC-01 |
| FR-02 | 变更单与基线 | R-CR-01 ~ R-CR-09 | C-ARC-02、C-ARC-06 | AC-02 ~ AC-04 |
| FR-03 | 审批与职责分离 | R-APR-01 ~ R-APR-05、R-AUTH-06 ~ R-AUTH-08 | C-ARC-09 | AC-05 ~ AC-07 |
| FR-04 | 发布、灰度与读回 | R-REL-01 ~ R-REL-07 | C-ARC-03 ~ C-ARC-05、C-ARC-20 | AC-08 ~ AC-10 |
| FR-05 | 审计与直连检测 | R-AUD-01 ~ R-AUD-07 | C-ARC-06、C-ARC-11 | AC-11 ~ AC-14 |
| FR-06 | 身份、授权与账号 | R-AUTH-01 ~ R-AUTH-10 | C-ARC-10、C-ARC-12 | AC-18、AC-26 |
| FR-07 | 影响面与风险 | R-IMP-01 ~ R-IMP-05 | C-ARC-13、C-ARC-14 | AC-06、AC-17 |
| FR-08 | 回滚、软删除与恢复 | R-RB-01 ~ R-RB-05、R-SYS-06 | C-ARC-19 | AC-15、AC-16 |
| FR-09 | 通知与集成 | R-NTF-01 ~ R-NTF-05 | C-ARC-07、C-ARC-12 | AC-19 |
| FR-10 | 多环境连接与映射 | R-CON-01 ~ R-CON-05 | C-ARC-17、C-ARC-18 | AC-23、AC-24 |
| FR-11 | 跨环境对比与受控同步 | R-SYN-01 ~ R-SYN-05 | C-ARC-03、C-ARC-09 | AC-20 |
| FR-12 | 定时发布与批量变更 | R-SCH-01 ~ R-SCH-06 | C-ARC-08、C-ARC-20 | AC-21、AC-22 |
| 横切 | 一致性、降级、租户与范围 | R-SYS-01 ~ R-SYS-10 | C-ARC-01 ~ C-ARC-20 | AC-23、AC-25 |
| 架构 | 模块边界与可测试性 | R-SYS-01 ~ R-SYS-03 | C-ARC-01 ~ C-ARC-20 | AC-08、AC-09、AC-11、AC-12 |
| 验收 | 报告与追踪 | 本规格 | — | 全部 |
| 细化 | 字段、状态机、错误码、接口约束 | R-AUTH-01 ~ R-SYS-10 | C-ARC-01 ~ C-ARC-20 | 静态评估 G-06 ~ G-08 |
| 文档 | 治理文档与执行约束 | R-SYS-01 ~ R-SYS-03、R-REL-02、R-REL-06 | C-ARC-03 ~ C-ARC-06 | 静态评估 G-16 |

## 附录 E：字段、状态、错误码与接口约束

> 本附录把附录 A 的规则落到可实现粒度。实现偏离本附录即视为规则违反；字段名为逻辑名，物理名可在实现时映射，但语义与约束不得改变。

### E.1 核心对象字段表

#### E.1.1 变更单 ChangeRequest

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| change_no | string(24) | 主键；格式 `CR-YYYYMMDD-NNNN`；全局唯一 | R-CR-01 |
| app_id / env_id | ULID | 非空；必须与 mapping 归属一致 | R-CAT-01 |
| mapping_id | ULID | 非空；指向唯一 TongNCS 坐标 | R-CAT-01、R-CON-04 |
| change_type | enum | CREATE / UPDATE / DELETE / RESTORE / ROLLBACK / SYNC | R-CR-01、R-RB-02 |
| title | string(120) | 非空；去空白后长度 ≥ 4 | R-CR-01 |
| reason | string(2000) | 非空；长度 ≥ 10 | R-CR-01 |
| content_ciphertext | blob | CREATE/UPDATE 必填；明文上限见附录 F | R-CR-07 |
| content_sha256 | char(64) | 与 content_ciphertext 同事务写入 | R-CR-07 |
| baseline_md5 | char(32) | 提交时冻结；不可回写 | R-CR-04 |
| baseline_snapshot_ref | ULID | 提交时冻结的 TongNCS 快照引用 | R-CR-04 |
| impact_snapshot_json | json | 提交时冻结；含实例数、应用、负责人与未知项 | R-IMP-01 |
| risk_level | enum | LOW / MEDIUM / HIGH；系统计算，用户只能上调 | R-IMP-03 |
| risk_factors_json | json | 与 risk_level 同事务写入 | R-IMP-02 |
| status | enum | 取值与迁移见 E.2.1 | R-CR-01 |
| version | bigint | 乐观锁；每次状态迁移 +1 | R-SYS-03 |
| submitter_id | ULID | 非空 | R-AUTH-06 |
| publisher_id | ULID | 发布前必须确定；可与提交人相同 | R-AUTH-06 |
| schedule_at | timestamp | 仅 SCHEDULED 必填；必须晚于当前时间 | R-SCH-01 |
| trace_id | string(32) | 非空；贯穿全链路 | C-ARC-14 |
| created_at / updated_at / closed_at | timestamp | UTC 存储；展示按用户时区；closed_at 仅终态写入 | R-SYS-03、R-AUD-02（通用字段） |

#### E.1.2 发布记录 ReleaseRecord

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| release_id | ULID | 主键 | R-REL-02 |
| release_key | string | 唯一索引；= `change_no + ':' + target_version` | R-REL-04 |
| change_no | string | 非空；关联变更单 | R-SYS-04 |
| mapping_id / env_id | ULID | 非空 | R-CAT-01 |
| source_type | enum | MANUAL / SCHEDULED / BATCH / ROLLBACK / RESTORE | R-RB-02、R-SCH-03 |
| target_version | string | 非空；一次发布尝试的版本标识 | R-REL-04 |
| outcome | enum | WRITING / VERIFIED / REJECTED / INDETERMINATE | R-REL-06 |
| reject_reason | enum | BASELINE / AUTH / UNAVAILABLE / INVALID；仅 REJECTED 可填 | R-REL-01、R-REL-06 |
| expected_md5 | char(32) | 非空；写入前计算 | R-REL-02 |
| readback_md5 | char(32) | VERIFIED 时非空且必须等于 expected_md5 | R-REL-02 |
| tongncs_receipt | json | 适配器回执；脱敏存储 | C-ARC-05 |
| error_code | string | 失败时必填；取值见 E.3 | R-SYS-03 |
| operator_id | ULID | 手工发布必填 | R-REL-01 |
| system_actor_id | ULID | 定时或批量代执行时必填 | R-SCH-03 |
| trace_id | string(32) | 非空 | C-ARC-14 |
| started_at / finished_at | timestamp | finished_at 仅终态写入 | R-REL-02 |

#### E.1.3 审计事件 AuditEvent

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| event_id | ULID | 主键 | R-AUD-02 |
| seq | bigint | 单调递增；当日链内唯一 | R-AUD-03 |
| occurred_at | timestamp | 非空 | R-AUD-02 |
| actor_type | enum | USER / SYSTEM | R-AUD-02、R-SCH-03 |
| actor_id | string | 用户 ID 或系统账号标识 | R-AUD-02 |
| source_ip | string(45) | 用户操作必填；系统任务填执行节点 | R-AUD-02 |
| action | enum | 固定枚举；新增需版本化 | R-AUD-01 |
| object_type / object_id | string | 非空 | R-AUD-02 |
| before_masked / after_masked | json | 敏感字段脱敏；禁止明文 | R-AUD-02、R-AUTH-09 |
| result | enum | SUCCESS / FAILURE | R-AUD-02 |
| error_code | string | FAILURE 时必填 | R-AUD-02 |
| change_no | string | 与变更相关时必填 | R-SYS-04 |
| trace_id | string(32) | 非空 | C-ARC-14 |
| prev_hash / event_hash | char(64) | 按日哈希链；不可修改 | R-AUD-03、C-ARC-11 |
| export_ref | string | 导出行为另生成事件并回填引用 | R-AUD-03 |

#### E.1.4 配置映射 ConfigMapping

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| mapping_id | ULID | 主键 | R-CAT-01 |
| app_id / env_id | ULID | 非空 | R-CAT-01 |
| namespace / group / data_id | string | 三者组合全局唯一；禁止一配置多归属 | R-CAT-01、R-CON-04 |
| format | enum | PROPERTIES / YAML / JSON / TEXT；由 data_id 后缀在纳管时识别并固化，不可变更 | R-CR-08、R-CR-09 |
| sensitive | boolean | 默认 false；识别方式见 A-05 | R-AUTH-09 |
| owner_id | ULID | 应用负责人；高风险审批与通知对象 | R-AUTH-07 |
| status | enum | MANAGED / UNMANAGED / DELETED | R-CAT-02、R-SYS-06 |
| last_snapshot_md5 | char(32) | 最近一次成功读取的 MD5 | R-CAT-03 |
| last_collected_at | timestamp | 快照采集时间；用于“可能过期”提示 | R-CAT-03 |
| version | bigint | 乐观锁 | E.5 |

#### E.1.5 实例 Instance

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| instance_id | ULID | 主键 | R-CON-01 |
| env_id | ULID | 唯一；一个实例只能绑定一个环境 | R-CON-01 |
| endpoint | string | 非空；HTTPS 或内网地址 | R-CON-03 |
| credential_ciphertext | blob | 加密存储；界面不回显 | R-CON-02、C-ARC-12 |
| status | enum | ONLINE / DEGRADED / UNAVAILABLE | R-CON-05 |
| consecutive_failures | int | 达到阈值置 UNAVAILABLE | R-CON-05 |
| last_probe_at / last_probe_latency_ms | timestamp / int | 探活结果 | R-CON-05 |
| version | bigint | 乐观锁 | E.5 |

#### E.1.6 授权 Grant

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| grant_id | ULID | 主键 | R-AUTH-03 |
| subject_type | enum | USER / GROUP / ROLE_TEMPLATE | R-AUTH-03 |
| subject_id | string | 非空 | R-AUTH-03 |
| app_id / env_id | ULID | 非空；“应用 × 环境”授权 | R-AUTH-03 |
| permission | enum | 固定 10 个权限点，见 R-AUTH-02 | R-AUTH-02 |
| granted_by | ULID | 非空 | R-AUD-01 |
| expires_at | timestamp | 可空；到期即失效 | R-AUTH-05 |
| created_at | timestamp | 非空 | R-AUD-01 |

唯一约束：`(subject_type, subject_id, app_id, env_id, permission)`。

#### E.1.7 定时任务 ScheduleJob

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| job_id | ULID | 主键 | R-SCH-01 |
| change_no | string | 活动中任务唯一；同一变更单不得并行多个任务 | R-SCH-01 |
| execute_at | timestamp | 非空；改期只改此字段 | R-SCH-01 |
| status | enum | 取值见 E.2.3 | R-SCH-02 |
| lease_owner / lease_expires_at | string / timestamp | 租约；过期可被接管 | C-ARC-08 |
| attempts | int | 复核或分派次数；不自动重试 | R-SCH-02 |
| last_error_code | string | 失败时必填 | R-SCH-02 |

#### E.1.8 批量 Batch 与 BatchItem

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| batch_id | ULID | 主键 | R-SCH-04 |
| parent_change_no | string | 非空；父单 | R-SCH-04 |
| status | enum | 取值见 E.2.4 | R-SCH-05 |
| total_count | int | ≤ 批量上限（附录 F） | R-SCH-06 |
| succeeded_count / failed_count | int | 与子项状态一致 | R-SCH-05 |
| item.change_no | string | `(batch_id, change_no)` 唯一 | R-SCH-05 |
| item.status | enum | PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED | R-SCH-05 |
| item.release_id / error_code | ULID / string | 终态时对应填写 | R-SCH-05 |

#### E.1.9 通知 Notification

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| notification_id | ULID | 主键 | R-NTF-01 |
| channel | enum | IN_APP / WECOM | R-NTF-01 |
| event_type | enum | 直连变更、发布失败、定时失败、审批等 | R-NTF-02 |
| object_type / object_id | string | 非空 | R-NTF-03 |
| payload_masked | json | 仅对象、动作、结果、操作人、链接；禁止明文 | R-NTF-03 |
| dedup_key | string | 事件类型 + 对象 + 聚合窗口 | R-NTF-05 |
| status | enum | QUEUED / SENDING / SENT / RETRYING / DEAD | R-NTF-04 |
| retry_count | int | ≤ 3 | R-NTF-04 |
| next_retry_at | timestamp | RETRYING 时必填 | R-NTF-04 |

#### E.1.10 直连变更事件 DirectChangeEvent

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| event_id | ULID | 主键 | R-AUD-06 |
| env_id / data_id | string | 非空 | R-AUD-06 |
| tongncs_history_id | string | 非空 | R-AUD-06 |
| detected_at | timestamp | 非空 | R-AUD-06 |
| match_release_id | ULID | 命中平台发布记录时填写 | R-AUD-06 |
| verdict | enum | MATCHED / UNAUTHORIZED / BLIND_SPOT | R-AUD-06、R-AUD-07 |
| notified_at / closed_at | timestamp | 通知与关闭时间 | R-NTF-02 |

唯一约束：`(env_id, data_id, tongncs_history_id)`；同一变更不得重复报警。

#### E.1.11 回收站 RecycleBinItem

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| item_id | ULID | 主键 | R-SYS-06 |
| mapping_id | ULID | 非空 | R-SYS-06 |
| deleted_at / deleted_by | timestamp / ULID | 非空 | R-SYS-06 |
| purge_at | timestamp | = deleted_at + 30 天 | R-SYS-06 |
| restore_status | enum | PENDING / RESTORED / PURGED | R-RB-04 |

#### E.1.12 同步快照 EnvSyncSnapshot

| 字段 | 类型 | 约束 | 来源 |
|---|---|---|---|
| snapshot_id | ULID | 主键 | R-SYN-05 |
| source_env / target_env | enum | dev / staging / prod；必须不同 | R-SYN-01 |
| config_count | int | 参与对比的逻辑配置数 | R-SYN-02 |
| diff_summary_json | json | 仅源存在、仅目标存在、内容不同 | R-SYN-02 |
| created_by / created_at | ULID / timestamp | 非空 | R-SYN-05 |
| expires_at | timestamp | 快照只读且可分享 | R-SYN-05 |

### E.2 状态机

#### E.2.1 变更单 ChangeRequest

| 当前状态 | 事件 | 目标状态 | 守卫 | 副作用 |
|---|---|---|---|---|
| DRAFT | submit | PENDING_APPROVAL | 必填完整、格式通过、内容未超限、同配置无进行中单、无同名冲突 | 冻结基线/影响面/风险；写审计；通知审批人 |
| PENDING_APPROVAL | approve | APPROVED | 非提交人；高风险仅应用负责人；审批人同时是发布人时仅应用负责人可通过 | 记录单人会签；写审计；通知提交人 |
| PENDING_APPROVAL | reject | DRAFT | 驳回理由 ≥ 10 字 | 保留审批意见；写审计；通知提交人 |
| PENDING_APPROVAL | transfer | PENDING_APPROVAL | 转交对象具备审批权限且合格 | 写审计；通知新旧审批人 |
| APPROVED | drift_detected | PENDING_APPROVAL | 重新读取 MD5 ≠ baseline_md5 | 展示三方 Diff；写审计；通知 |
| APPROVED | schedule | SCHEDULED | schedule_at 晚于当前时间且窗口允许 | 创建 ScheduleJob；写审计 |
| APPROVED | publish | RELEASING | 六项预检全部通过 | 创建 ReleaseRecord；写意图审计 |
| SCHEDULED | fire | RELEASING | 到点复核通过 | 系统账号代执行；双主体审计 |
| SCHEDULED | recheck_failed | APPROVED | 任一复核失败 | 告警；不写入；不自动重试 |
| SCHEDULED | cancel_schedule | APPROVED | 具备发布权限 | 取消任务；写审计 |
| RELEASING | verified | SUCCEEDED | 读回 MD5 与期望一致 | 结果审计；通知 |
| RELEASING | rejected | FAILED | 适配器返回 rejected | 结果审计；告警 |
| RELEASING | indeterminate | INDETERMINATE | 写入已发出但无法确认 | 高优告警；禁止自动重试 |
| INDETERMINATE | reconcile_success | SUCCEEDED | 人工对账确认已生效且读回一致 | 对账审计 |
| INDETERMINATE | reconcile_failure | FAILED | 人工对账确认未生效 | 对账审计；可新建变更单 |
| DRAFT / APPROVED / SCHEDULED | cancel | CANCELLED | 具备提交或发布权限 | 写审计；释放配置占用 |

不变量：

- 仅 `RELEASING` 可调用 TongNCS 写方法；其他状态一律只读。
- `SUCCEEDED` / `FAILED` / `CANCELLED` 为终态；仅 `INDETERMINATE` 可经人工对账进入终态。
- 每次迁移必须与审计同事务，使用 `WHERE status = ? AND version = ?` 条件更新。
- 同一 `mapping_id` 最多存在 1 个非 DRAFT 且非终态的变更单（R-CR-03）。

#### E.2.2 发布记录 ReleaseRecord

| 当前状态 | 事件 | 目标状态 | 守卫 | 副作用 |
|---|---|---|---|---|
| CREATED | start | WRITING | 预检通过且幂等键未命中 | 写意图审计 |
| WRITING | verified | VERIFIED | 读回 MD5 等于期望值 | 写结果审计；更新变更单 |
| WRITING | rejected | REJECTED | 适配器返回 rejected 且原因属于四类 | 写结果审计；告警 |
| WRITING | indeterminate | INDETERMINATE | 超时、连接中断或读回失败 | 高优告警；禁止自动重试 |
| INDETERMINATE | reconcile | VERIFIED / REJECTED | 人工对账提供证据 | 追加对账记录，不改历史字段 |

#### E.2.3 定时任务 ScheduleJob

| 当前状态 | 事件 | 目标状态 | 守卫 | 副作用 |
|---|---|---|---|---|
| SCHEDULED | trigger | TRIGGERED | 到达执行时间且租约可用 | 记录触发时间 |
| TRIGGERED | precheck | PRECHECK | 成功获得租约 | 开始六项复核 |
| PRECHECK | dispatch | DISPATCHED | 复核全部通过 | 系统代执行发布 |
| PRECHECK | return | RETURNED | 任一复核失败 | 变更单转回 APPROVED；告警；不自动重试 |
| SCHEDULED | cancel | CANCELLED | 用户取消 | 写审计 |
| DISPATCHED | finish | DONE | 发布记录进入终态 | 回写结果 |

#### E.2.4 批量 Batch

| 当前状态 | 事件 | 目标状态 | 守卫 | 副作用 |
|---|---|---|---|---|
| CREATED | start | RUNNING | 父单审批通过且子项数未超限 | 逐项发布 |
| RUNNING | all_success | SUCCEEDED | 全部子项成功 | 汇总审计 |
| RUNNING | partial | PARTIAL_SUCCESS | 至少 1 成功且至少 1 失败 | 标记失败子项可重试 |
| RUNNING | all_failed | FAILED | 全部子项失败 | 告警 |
| PARTIAL_SUCCESS | retry_failed | PARTIAL_SUCCESS / SUCCEEDED | 仅重试失败子项 | 成功子项不得重发 |

#### E.2.5 直连变更事件 DirectChangeEvent

| 当前状态 | 事件 | 目标状态 | 守卫 | 副作用 |
|---|---|---|---|---|
| DETECTED | match | MATCHED | 命中平台发布记录（环境+配置+历史 ID） | 仅记录，不报警 |
| DETECTED | no_match | UNAUTHORIZED | 未命中平台记录 | 写审计；5 分钟内通知 |
| DETECTED | blind | BLIND_SPOT | 历史不可用或存在缺口 | 标注盲区，不推断“无变更” |
| UNAUTHORIZED | notify | NOTIFIED | 站内与企业微信至少一路成功 | 记录通知结果 |
| NOTIFIED | close | CLOSED | 人工确认或负责人响应 | 写关闭审计 |

#### E.2.6 通知、实例与回收站

| 对象 | 迁移 | 守卫 | 副作用 |
|---|---|---|---|
| Notification | QUEUED → SENDING → SENT | 渠道可用 | 记录发送结果 |
| Notification | SENDING → RETRYING → SENDING / DEAD | retry_count ≤ 3 | 最终失败写审计，不阻塞业务 |
| Instance | ONLINE → DEGRADED → UNAVAILABLE | 连续探活失败达阈值 | 阻断该环境写操作 |
| Instance | UNAVAILABLE → ONLINE | 探活恢复 | 仅恢复可用性，不补发历史操作 |
| RecycleBinItem | PENDING → RESTORED | 30 天内且无同名冲突或已确认覆盖 | 写 TongNCS 并读回 |
| RecycleBinItem | PENDING → PURGED | 超过 30 天 | 清除并写审计 |

### E.3 错误码表

> 编码格式为 `域-HTTP-序号`；`可重试` 指同一请求原样重试是否安全。

| 错误码 | 触发条件 | HTTP | 可重试 | 处理建议 |
|---|---|---|---|---|
| AUTH-401-001 | 未登录或会话失效 | 401 | 否 | 重新登录 |
| AUTH-401-002 | 账号被禁用或冻结 | 401 | 否 | 联系管理员 |
| AUTHZ-403-001 | 缺少权限点 | 403 | 否 | 申请授权 |
| AUTHZ-403-002 | 提交人或发布人审批冲突 | 403 | 否 | 更换审批人 |
| AUTHZ-403-003 | 高风险审批人非应用负责人 | 403 | 否 | 指定负责人或代理人 |
| CAT-409-001 | 配置已被其他应用或环境映射 | 409 | 否 | 先解除原映射 |
| CAT-409-002 | 纳管动作与现有映射冲突 | 409 | 否 | 先处理未纳管项 |
| CR-400-001 | 必填缺失或格式错误 | 400 | 否 | 修正后重新提交 |
| CR-409-001 | 同配置已有进行中变更单 | 409 | 否 | 使用已有单号 |
| CR-412-001 | 提交或发布前基线漂移 | 412 | 否 | 重新审批 |
| CR-413-001 | 内容超过上限 | 413 | 否 | 拆分或申请调整 |
| CR-415-001 | DataId 后缀无法识别或格式不支持 | 415 | 否 | 改用受支持后缀或只读浏览 |
| CR-422-001 | 配置内容解析失败（响应附行号/列号） | 422 | 否 | 按行修正后重新提交 |
| APR-409-001 | 变更单状态不允许审批 | 409 | 否 | 刷新状态 |
| APR-422-001 | 驳回缺少理由 | 422 | 否 | 补充理由 |
| REL-403-001 | 无发布权限 | 403 | 否 | 申请授权 |
| REL-409-001 | 变更单状态不允许发布 | 409 | 否 | 刷新状态 |
| REL-409-002 | 幂等命中，返回既有发布记录 | 200 | 否 | 展示既有记录 |
| REL-412-001 | 发布前基线不一致 | 412 | 否 | 退回审批 |
| REL-422-001 | 发布窗口外 | 422 | 否 | 等待窗口或调整计划 |
| REL-502-001 | 写入结果不确定 | 502 | 否 | 人工对账 |
| REL-503-001 | 实例不可用或连接失败 | 503 | 是 | 实例恢复后重试 |
| RB-410-001 | 超过 30 天不可恢复 | 410 | 否 | 从 TongNCS 历史另建变更 |
| RB-409-001 | 恢复时同名冲突 | 409 | 否 | 选择覆盖并审批或放弃 |
| AUD-500-001 | 审计写入失败 | 500 | 是 | 整笔业务失败后重试 |
| IMP-409-001 | 影响面缺失阻断高风险发布 | 409 | 否 | 补齐订阅映射 |
| NTF-502-001 | 通知渠道失败 | 502 | 是（≤3 次） | 降级站内通知 |
| CON-409-001 | 实例已被其他环境绑定 | 409 | 否 | 更换实例 |
| CON-502-001 | 连接测试失败 | 502 | 是 | 修正凭证或网络 |
| SYN-409-001 | 目标环境已有进行中变更单 | 409 | 否 | 使用目标单号 |
| SYN-409-002 | 跨环境映射或格式不一致 | 409 | 否 | 先对齐映射 |
| SCH-409-001 | 定时任务状态不允许操作 | 409 | 否 | 刷新状态 |
| SCH-409-002 | 到点复核失败 | 409 | 否 | 重新计划并重新审批 |
| BAT-413-001 | 批量超过上限 | 413 | 否 | 拆分批次 |
| SYS-403-001 | 不支持紧急发布 | 403 | 否 | 无替代通道 |
| SYS-423-001 | 环境只读降级 | 423 | 否 | 等待实例恢复 |
| SYS-404-001 | 对象不存在或已删除 | 404 | 否 | 刷新列表 |
| SYS-409-001 | 乐观锁冲突 | 409 | 是 | 重新读取后重试 |
| SYS-500-001 | 未分类内部错误 | 500 | 视情况 | 上报 TraceId |

### E.4 接口级约束

> 外部 TongNCS 调用不得置于数据库事务内；状态与审计同事务，外部写入采用“意图 + 结果”两段记录。

| 模块 | 关键操作 | 权限点 | 事务边界 | 幂等 / 并发 | 主要错误 |
|---|---|---|---|---|---|
| Catalog | 列表、详情、读取 | 查看 | 只读，不持事务 | 快照按 mapping 缓存 | SYS-404、CON-502、SYS-423 |
| Mapping | 创建、更新、删除、导入 | 平台管理 | 单事务：映射 + 审计；导入逐行校验后整批提交 | `unique(namespace, group, data_id)`；version | CAT-409-001、CAT-409-002 |
| ChangeDraft | 保存、提交 | 编辑 / 提交 | 单事务：草稿 + 基线快照 + 影响面 + 风险 + 审计 | 条件更新 version；同配置唯一进行中单 | CR-409-001、CR-412-001、CR-413-001 |
| Approval | 通过、驳回、转交 | 审批 | 单事务：状态 + 审批记录 + 审计 | `WHERE status=PENDING_APPROVAL AND version=?` | AUTHZ-403-002/003、APR-409-001、APR-422-001 |
| Release | 发布、回滚、恢复、对账 | 发布 / 回滚 | 三事务：意图记录 → 外部写入（事务外）→ 结果记录 | `release_key` 唯一；状态条件迁移 | REL-403/409/412/422/502/503 |
| Impact | 计算、刷新 | 查看 | 只读派生，结果落快照 | 同 mapping 短时去重 | IMP-409-001 |
| EnvSync | 对比、同步 | 查看 / 编辑 | 对比只读；同步单事务创建目标变更单 + 审计 | 目标唯一进行中单 | SYN-409-001、SYN-409-002 |
| Scheduler | 计划、取消、改期 | 发布 | 单事务：任务 + 审计 | 活动任务唯一；租约 | SCH-409-001 |
| Batch | 创建、提交、重试子项 | 编辑 / 发布 | 父单与子项同事务创建；逐项发布独立事务 | 子项唯一；上限见附录 F | BAT-413-001、REL-409-001 |
| AuditTrail | 查询、导出 | 查看审计 / 导出审计 | 只读；导出行为另写审计 | 导出文件含校验值；append-only | AUTHZ-403-001、AUD-500-001 |
| DirectScan | 扫描、通知、关闭 | 系统 | 每环境租约；结果批量写入 + 审计 | `unique(env_id, data_id, history_id)` | AUD-500-001、SYS-423-001 |
| AuthZ | 鉴权、授权、回收 | 平台管理（授权） | 授权单事务 + 审计；鉴权无状态 | 权限变更即时失效 | AUTHZ-403-001 |
| Notify | 入队、发送 | 系统 | 发件箱与业务同事务入队；发送独立 | `dedup_key` + 聚合窗口 | NTF-502-001 |
| RecycleBin | 列表、恢复、清除 | 发布 / 系统 | 恢复：外部写入（事务外）+ 状态 + 审计 | `item_id + target_version` | RB-409-001、RB-410-001 |
| Adapter | read / publish / history / subscribers / test | 内部，不做权限判断 | 不持业务事务；超时 + 熔断 | `publish` 使用幂等键；三态返回 | REL-502-001、REL-503-001 |

### E.5 并发、幂等与唯一约束

| 场景 | 键 / 机制 | 冲突行为 |
|---|---|---|
| 发布 | `release_key = change_no + target_version` 唯一索引 | 命中返回既有记录，不写 TongNCS（REL-409-002） |
| 重复点击 / Worker 重放 | 同上 + 任务租约 | 返回既有结果，不产生第二次写入 |
| 建单 | 部分唯一索引：`mapping_id WHERE status IN (PENDING_APPROVAL, APPROVED, SCHEDULED, RELEASING, INDETERMINATE)` | 阻断并返回 CR-409-001 |
| 映射 | `unique(namespace, group, data_id)` | CAT-409-001 |
| 实例 | `unique(env_id)`，且实例不得出现在多个环境 | CON-409-001 |
| 授权 | `unique(subject_type, subject_id, app_id, env_id, permission)` | 重复授权幂等；冲突拒绝 |
| 直连检测 | `unique(env_id, data_id, tongncs_history_id)` | 重复检测不重复报警 |
| 通知 | `dedup_key = event_type + object + 窗口起点` | 窗口内合并发送 |
| 审计 | 按日 `seq` + 哈希链；append-only | 写入失败则业务失败（AUD-500-001） |
| 状态迁移 | `version` 乐观锁 + `WHERE status = ?` | 冲突返回 SYS-409-001 |
| 定时任务 | `lease_owner + lease_expires_at`，按时续租 | 租约过期可被接管；任务本身幂等 |
| 批量子项 | `unique(batch_id, change_no)` | 已成功子项不得重发 |
| 恢复 | `item_id + target_version` | 重复恢复返回既有结果 |

补充约束：

- 所有外部调用必须设置超时与重试上限，具体值见附录 F；超时映射为领域错误，不向上暴露原始异常。
- 乐观锁冲突最多自动重试 1 次，之后返回 SYS-409-001。
- Worker 租约 30 秒、续租 10 秒；扫描租约 5 分钟 [Assumption, TBD]。
- 发件箱与业务状态同事务写入，异步发送；发送失败只影响通知状态，不回滚业务。

## 附录 F：默认参数表

> 标注“固定”的参数来自 PRD 决策或规则，不得在实现中擅自修改；标注 `[Assumption, TBD]` 或 `[TBD]` 的参数需评审确认后才能冻结。

| 参数 | 默认值 | 依据 / 说明 | 状态 |
|---|---|---|---|
| 单配置内容上限 | 1 MB | 影响导入、Diff 与存储 | [Assumption, TBD]（A-02） |
| 单次批量上限 | 50 项 | 超限返回 BAT-413-001 | [Assumption, TBD]（A-03） |
| 灰度观察窗口 | 30 分钟 | R-REL-03 | [Assumption, TBD]（A-04） |
| 直连检测周期 | 5 分钟 | R-AUD-06 | 固定 |
| 直连通知时限 | 检测后 5 分钟内 | R-AUD-06 | 固定 |
| 通知重试次数 | 3 | R-NTF-04 | 固定 |
| 通知退避 | 1 / 5 / 15 分钟 | 三次重试间隔 | [Assumption, TBD] |
| 通知聚合窗口 | 5 分钟 | R-NTF-05 | [Assumption, TBD] |
| 回收站保留 | 30 天 | PRD 决策 | 固定 |
| 审计在线保留 | 13 个月 | 查询性能与成本 | [Assumption, TBD]（A-07） |
| 审计归档保留 | 永久 | PRD 决策；保留期固定，归档介质待定 | 保留期固定；介质 [Assumption, TBD]（A-07） |
| 发布窗口 | 全天 | PRD 决策 | 固定 |
| 审批超时 | 无 | PRD 决策 | 固定 |
| 高风险 Diff 阈值 | 待定（建议 > 200 行） | A-01 | [TBD] |
| 高风险实例阈值 | 待定（建议 > 50 实例） | A-01 | [TBD] |
| 高风险时间窗 | 待定（建议 22:00–06:00） | A-01 | [TBD] |
| 会话空闲超时 | 30 分钟 | 自建账号 | [Assumption, TBD] |
| 密码最小长度 | 12 位，含大小写、数字与符号 | 自建账号 | [Assumption, TBD] |
| 密码更换周期 | 90 天 | 自建账号 | [Assumption, TBD]（A-08） |
| 登录失败锁定 | 5 次 / 15 分钟 | 自建账号 | [Assumption, TBD]（A-08） |
| 连接超时 | 5 秒 | C-ARC-13 | [Assumption, TBD] |
| 读 / 写超时 | 10 秒 | C-ARC-13 | [Assumption, TBD] |
| 读回超时 | 5 秒 | R-REL-02 | [Assumption, TBD] |
| 实例探活周期 | 60 秒 | R-CON-05 | [Assumption, TBD] |
| 判不可用阈值 | 连续 3 次失败 | R-CON-05 | [Assumption, TBD] |
| Worker 租约 | 30 秒（续租 10 秒） | C-ARC-08 | [Assumption, TBD] |
| 扫描租约 | 5 分钟 | C-ARC-08 | [Assumption, TBD] |
| Diff 展示上限 | 5000 行（超出折叠） | 页面性能 | [Assumption, TBD] |
| 审计单次导出上限 | 10 万条 | 导出性能 | [Assumption, TBD] |
| 乐观锁自动重试 | 1 次 | E.5 | 固定 |

## 附录 G：静态验收评估报告

> 评估类型：静态（文档级），不执行系统。  
> 输入：PRD v1.1、系统架构设计 v1.0、本规格 v1.2。  
> 方法：条款比对、编号交叉引用、字段/状态/错误码/接口可执行性检查。  
> 结论：**12 PASS / 4 BLOCKED / 0 FAIL**，P0 文档级完备度 75%（12/16）。  
> 声明：PASS 表示文档自洽且可实现，不代表运行时通过；运行时验收必须按 C.2 模板另出报告。

### G.1 评估明细

| 编号 | 评估项 | 结论 | 证据 / 依据 | 缺口与动作 |
|---|---|---|---|---|
| G-01 | P0 范围与 12 项 FR 覆盖 | PASS | 16 个 Requirement；附录 A、D | 无 |
| G-02 | TongNCS 唯一事实源与唯一写入口 | PASS | R-SYS-01/02、C-ARC-02/03、E.4 Release | 无 |
| G-03 | 适配器五方法与三态 | PASS | C-ARC-04/05、E.1.2、E.2.2 | 无 |
| G-04 | 状态与审计同事务、两段审计 | PASS | C-ARC-06、E.2.1、E.5 | 无 |
| G-05 | 权限与职责分离 | PASS | R-AUTH-05 ~ R-AUTH-08、E.2.1 守卫 | 无 |
| G-06 | 字段级规则完整 | PASS | E.1 覆盖 12 类对象 | 无 |
| G-07 | 错误码完备 | PASS | E.3 共 37 条，含 HTTP 与可重试性 | 无 |
| G-08 | 接口事务、幂等与并发约束 | PASS | E.4、E.5 | 无 |
| G-09 | 软删除与恢复 | PASS | R-SYS-06、R-RB-04、E.1.11 | 无 |
| G-10 | 直连检测与盲区 | PASS | R-AUD-06/07、E.2.5 | 无 |
| G-11 | 多环境独立实例 | PASS | R-CON-01、C-ARC-17、E.1.5 | 无 |
| G-12 | 验收设计（场景/证据/模板/追踪） | PASS | 附录 C、D；AC-01 ~ AC-26 | 运行时证据需在实现后采集 |
| G-13 | 高风险阈值可执行 | BLOCKED | 附录 F；A-01 | 产品需给出 Diff、实例数、时间窗阈值 |
| G-14 | 批量上限与部分失败可执行 | BLOCKED | 附录 F；A-03 | 产品需确认 50 项上限；部分成功策略已明确 |
| G-15 | 审计保留合规 | BLOCKED | R-AUD-04、附录 F；A-07 | 合规与运维需给出在线/归档介质、访问与销毁方案 |
| G-16 | 治理文档 agent.md / rule.md | BLOCKED | 本规格新增 Requirement | 需在实现阶段产出并通过一致性校验 |

### G.2 缺口闭环动作

| BLOCKED | 责任方 | 关闭标准 | 影响的验收 |
|---|---|---|---|
| G-13 | 产品负责人 | 三项阈值写入附录 F，去掉 `[TBD]` | AC-06 可用确定数据复现 |
| G-14 | 产品负责人 | 确认批量上限与超限策略 | AC-22 可构造超限与部分成功用例 |
| G-15 | 合规、运维 | 给出在线保留期、归档介质、访问与销毁策略 | R-AUD-04 可审计且可证明 |
| G-16 | 实现者 | 产出 `agent.md`、`rule.md` 并通过一致性校验 | 文档漂移检查通过 |

### G.3 静态结论

- 不存在 FAIL 项；P0 规则、约束、错误码与接口边界已可指导实现。
- 4 项 BLOCKED 均为数值或文档交付缺口，不影响架构与规则结构；不阻断文档基线冻结，但阻断 P0 验收。
- 在 BLOCKED 闭环前，不得宣称 P0 已验收；文档基线可作为实现依据冻结。

## 假设与待确认

| 编号 | 事项 | 当前处理 | 影响 |
|---|---|---|---|
| A-01 | 高风险判定的具体阈值（Diff 行数、实例数、时间窗） | 规则定义因子，阈值作为平台配置 [TBD] | 影响审批人选择与验收用例数据 |
| A-02 | 单个配置内容上限 | 默认 1 MB [Assumption, TBD] | 影响导入、Diff 与存储 |
| A-03 | 单次批量上限 | 默认 50 项 [Assumption, TBD] | 影响批量任务拆分 |
| A-04 | 灰度观察窗口 | 默认 30 分钟 [Assumption, TBD] | 影响发布节奏与告警 |
| A-05 | 敏感配置识别方式（命名、标记、正则） | 规则要求可识别，具体方式 [TBD] | 影响脱敏、权限与风险 |
| A-06 | 应用负责人代理人的指定与有效期 | 要求审计，具体流程 [TBD] | 影响审批连续性 |
| A-07 | 审计在线保留期与永久归档的合规方案 | 在线 13 个月、永久归档为默认假设 [Assumption, TBD]；归档介质、访问审计与销毁流程待定 | 阻断 G-15；影响 R-AUD-04 的可证明性 |
| A-08 | 自建账号的密码策略、登录锁定与会话超时 | 附录 F 给出建议默认值 [Assumption, TBD] | 影响安全基线与登录类验收 |

## 版本与变更记录

| 版本 | 日期 | 变更 | 作者 | 状态 |
|---|---|---|---|---|
| v1.0 | 2026-11-21 | 初稿：81 条规则、20 条约束、26 个验收场景、验收报告模板与追踪矩阵 | TRAE | 已驳回 |
| v1.1 | 2026-11-21 | 按驳回意见细化：新增字段、状态机、错误码、接口级约束、默认参数与静态验收评估 | TRAE | 已被 v1.2 取代 |
| v1.2 | 2026-11-21 | 补齐附录 E（12 类对象字段、6 组状态机、37 条错误码、15 个模块接口约束、并发幂等唯一约束）、附录 F（30 项默认参数）、附录 G（静态评估 12 PASS / 4 BLOCKED / 0 FAIL）；新增 agent.md / rule.md 交付要求与追踪维度；评审前一致性修订：R-IMP-02 与 R-SYS-07 标注、审计归档状态，agent.md 约束改用 AG-xx 编号 | TRAE | 已被 v1.3 取代 |
| v1.3 | 2026-11-21 | 重定义治理文档职责：`agent.md` 改为项目上下文说明书，`rule.md` 改为 MUST / MUST-NOT 行为红线；完整规则目录保留附录 A，字段、状态机、错误码保留附录 E；同步修改治理文档 Requirement 与三个 Scenario | TRAE | 已被 v1.4 取代 |
| v1.4 | 2026-11-22 | 补充配置格式支持：新增 R-CR-08 / R-CR-09（后缀识别、properties / YAML 解析、失败定位、未知格式只读）；`ConfigMapping` 增加 `format` 字段；R-SYN-04 明确格式不一致；R-CR-01 明确结构化 `items[]` 与 `content` 的互斥关系；新增错误码 `CR-415-001`、`CR-422-001` | TRAE | 有效 |

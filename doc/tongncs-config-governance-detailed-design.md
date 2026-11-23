# TongNCS 配置治理平台（NCG）详细设计方案

> 版本：v1.4  
> 日期：2026-11-22  
> 状态：待评审  
> 输入：PRD v1.1、系统架构设计 v1.0、spec v1.4、rule.md、agent.md、静态验收评估报告 v1.1  
> 范围：技术实现设计，不含可运行代码、脚手架与数据库迁移脚本

## 0 文档信息

### 0.1 目的

把 PRD、架构稿与规格中的规则，落到 Java / Spring Boot 可实现的结构、数据、接口、事务与失败处理，并补齐前端交互契约。读者是后端与前端开发、测试、运维与评审者。

### 0.2 与上游文档的关系

| 文档 | 作用 | 本设计的使用方式 |
|---|---|---|
| PRD v1.1 | 产品范围与 12 项 P0 功能 | 只实现 P0，不扩张 |
| 架构设计 v1.0 | 模块、接缝、部署形态 | 不改变模块边界与适配器接缝 |
| spec v1.4 | 规则、约束、字段、状态机、错误码、验收 | 字段与状态以其为准；冲突时以 spec 为准 |
| rule.md | 行为红线 | 设计必须满足 14 条 MUST / 10 条 MUST NOT |
| 静态验收报告 | 文档级评估与 BLOCKED 项 | 未决项在第 19 章承接 |

### 0.3 术语

- **模块**：有接口与实现的单元；本设计中对应一个 Java 包及其应用接口。
- **接缝**：模块接口所在位置；TongNCS 接缝固定为 `TongNcsConfigPort`。
- **适配器**：接缝的具体实现；本设计有真实适配器与测试假件两个实现。
- **快照**：某一时刻从 TongNCS 读取的内容与 MD5，带采集时间，不是事实源。
- **基线**：变更单提交时冻结的配置 MD5 与快照引用。
- **发件箱**：与业务同事务写入、异步发送的通知记录。
- **租约**：Worker 通过数据库持有的、带过期时间的执行权。

### 0.4 设计原则

1. TongNCS 是配置内容唯一事实源，平台只存治理元数据。
2. Release 是唯一写入口；其他模块只读。
3. 状态与审计同事务；外部写入采用“意图 + 结果”两段记录。
4. 不确定结果不自动重试，交人工对账。
5. 外部调用不放进数据库事务；所有外部调用有超时。
6. 模块间只经应用接口调用，不跨模块读表。
7. 能在接口层测试的，不穿过接口测试。

### 0.5 版本记录

| 版本 | 日期 | 变更 | 状态 |
|---|---|---|---|
| v1.0 | 2026-11-21 | 初版，20 章（0~19） | 已被 v1.1 取代 |
| v1.1 | 2026-11-21 | 补齐平台自有表字段、敏感值查看端点、应用 × 环境通知绑定、37 条错误码到入口的完整映射、审计动作与通知事件枚举、批量执行任务；修正 worker 管理端口与只读降级错误码；同步周边文档 | 已被 v1.2 取代 |
| v1.2 | 2026-11-22 | 新增 3.5 前端交互设计：设计原则、信息架构、关键交互契约、页面状态、视觉系统、可访问性与后端契约；新增可点击单文件原型 `ncg-frontend-prototype.html` | 已被 v1.3 取代 |
| v1.3 | 2026-11-22 | 补充配置格式支持：新增 4.5 配置格式与解析契约（PROPERTIES / YAML / JSON / TEXT）、`ConfigFormat` 值对象与 `ConfigContentCodec` 服务；3.5 增加格式编辑与校验失败契约；原型增加 properties / yml 示例与格式标签 | 已被 v1.4 取代 |
| v1.4 | 2026-11-22 | 补齐前端交互引出的 5 个后端契约缺口：`ConfigMapping.format` 持久化列；`GET /configs/{mappingId}` 返回 `items[]`；敏感值查看按 `itemKey`；变更单以 `items[]` 为主契约（`content` 仅用于 TEXT / 整文件替换）；批量创建按 DataId 提交逐项值并新增草稿编辑值端点；同步数据库字段、接口清单、请求示例与旧前端设计文档 | 有效 |

## 1 设计目标与约束

### 1.1 不变量

| 编号 | 不变量 | 设计落点 |
|---|---|---|
| INV-01 | 运行时配置只存在于 TongNCS | 目录与详情只经适配器读取，快照标注采集时间 |
| INV-02 | 只有 Release 可写 TongNCS | 适配器 `publish` 只被 Release 应用服务调用 |
| INV-03 | 状态变更与审计同事务 | 每个用例边界内写业务表与 `ncg_audit_event` |
| INV-04 | 审计不可改、不可删 | 数据库账号只授予 INSERT/SELECT；触发器拒绝 UPDATE/DELETE |
| INV-05 | 发布必须读回校验 | `release_record.expected_md5 = readback_md5` 才算 VERIFIED |
| INV-06 | `indeterminate` 不自动重试 | 无重试任务；只能人工对账 |
| INV-07 | 实例不可用时只读 | 发布/回滚/恢复/灰度前置检查实例状态 |
| INV-08 | 权限判断失败即拒绝 | 鉴权异常统一转 403，不降级放行 |

### 1.2 P0 边界

- 支持：目录、变更单、审批、发布与灰度、审计、权限、影响面、回滚、通知、多环境连接、跨环境同步、定时与批量。
- 不支持：SSO、多租户、紧急发布、消息中间件、微服务、CI/CD、ITSM、KMS、双向同步。

### 1.3 质量属性

| 属性 | 设计手段 |
|---|---|
| 正确性 | 状态机守卫、唯一约束、乐观锁、读回校验 |
| 可审计 | 全链路事件、两段审计、哈希链、TraceId |
| 可运维 | 健康检查、指标、告警、租约与恢复 |
| 可测试 | 端口与假件、契约测试、Testcontainers |
| 安全性 | 自建账号、fail-closed、AES-GCM、脱敏 |

## 2 总体架构

### 2.1 逻辑架构

```text
Browser
  │ HTTPS
  ▼
ncg-web (Spring MVC, 无状态, 2+ 实例)
  ├─ AuthZ ──► ncg-auth
  ├─ Catalog / Change / Approval / Release / Impact
  ├─ EnvSync / Scheduler / Batch / RecycleBin
  ├─ AuditTrail ──► ncg-audit
  └─ Release ──► TongNcsConfigPort ──► TongNCS dev/staging/prod
                         ▲
ncg-worker (单活 + 热备)  │
  ├─ ScheduleJob / Batch / DirectScan / Outbox
  └─ 通过同一端口读取与发布
                         │
                    PostgreSQL 16
```

### 2.2 部署拓扑

| 组件 | 实例 | 说明 |
|---|---|---|
| ncg-web | 2+ | 无状态；会话存数据库；可水平扩展 |
| ncg-worker | 1 活 + 1 备 | 通过 `worker:active` 租约选主；备节点待命 |
| PostgreSQL | 主 + 备 | 单库；审计账号只读与追加 |
| TongNCS | dev / staging / prod 三套 | 每环境独立实例，不复用 |

### 2.3 模块职责

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `auth` | 登录、会话、账号、授权、鉴权 | `shared` |
| `catalog` | 应用、环境、映射、目录读取 | `auth.api`、`adapter`、`audit.api` |
| `change` | 变更单草稿、提交、基线冻结 | `catalog.api`、`impact.api`、`audit.api` |
| `approval` | 通过、驳回、转交、审批记录 | `change.api`、`auth.api`、`audit.api` |
| `release` | 预检、发布、回滚、恢复、对账 | `change.api`、`adapter`、`audit.api`、`notification.api` |
| `impact` | 订阅者、影响面、风险计算 | `adapter`、`catalog.api` |
| `envsync` | 三环境对比、生成目标变更单 | `catalog.api`、`change.api`、`audit.api` |
| `scheduler` | 定时任务、租约、到点复核 | `release.api`、`change.api`、`audit.api` |
| `batch` | 批量父单、子项、部分成功 | `change.api`、`release.api`、`audit.api` |
| `notification` | 发件箱、企微、站内通知 | `shared` |
| `audit` | 事件写入、查询、导出、哈希链 | `shared` |
| `adapter` | TongNCS 五方法、三态、契约测试 | `shared` |

### 2.4 依赖规则

1. 依赖方向：`web → 应用服务 → 领域/仓储 → 适配器`。
2. 模块间只调用 `*.api` 包中的接口，不引用 `*.repo`。
3. 禁止跨模块 SQL JOIN；跨模块查询由应用层组装。
4. `adapter` 不被 `web` 直接调用；只有 `release` 调用 `publish`，其他模块只调用 `read/history/subscribers/test`。

### 2.5 数据所有权

| 数据 | 所有者 | 存储 |
|---|---|---|
| 配置内容 | TongNCS | 不落平台库 |
| 映射、变更单、审批、发布记录 | 对应治理模块 | PostgreSQL |
| 审计事件 | `audit` | PostgreSQL，append-only |
| 快照与影响面 | `catalog` / `impact` | PostgreSQL，派生数据 |
| 通知状态 | `notification` | PostgreSQL |
| 回收站 | `release` / `catalog` | PostgreSQL，30 天 |

## 3 工程结构

### 3.1 Maven 与启动类

- 单 Maven 项目，Java 21，Spring Boot 3.3.x。
- 两个启动类：
  - `NcgWebApplication`：REST、会话、页面资源。
  - `NcgWorkerApplication`：调度、发件箱、直连扫描、批量执行。
- 同一代码库、同一领域与适配器实现，避免逻辑分叉。

### 3.2 包结构

```text
com.<org>.ncg
├── web                 # Controller、DTO、异常处理、拦截器
├── shared              # ULID、时间、错误、Trace、脱敏、加密、事务工具
├── auth                # api / app / domain / repo
├── catalog             # api / app / domain / repo
├── change              # api / app / domain / repo
├── approval            # api / app / domain / repo
├── release             # api / app / domain / repo
├── impact              # api / app / domain / repo
├── envsync             # api / app / domain / repo
├── scheduler           # api / app / domain / repo
├── batch               # api / app / domain / repo
├── notification        # api / app / domain / repo
├── audit               # api / app / domain / repo
└── adapter.tongncs     # port / real / fake / dto / config
```

### 3.3 模块内分层

| 层 | 职责 | 约束 |
|---|---|---|
| `api` | 对外应用接口与 DTO | 不暴露持久化对象 |
| `app` | 用例编排、事务边界 | 一个用例一个事务；外部调用在事务外 |
| `domain` | 聚合、状态机、守卫 | 不依赖 Spring |
| `repo` | SQL、映射、唯一约束 | 只访问本模块表 |

### 3.4 配置与 Profile

| Profile | 启动 | 说明 |
|---|---|---|
| `web` | `NcgWebApplication` | 开启 REST，不开启调度 |
| `worker` | `NcgWorkerApplication` | 仅开启调度、发件箱、扫描 |
| `local` | 两者 | 假适配器、内存或本地 PostgreSQL |

`worker` 设 `server.port=-1` 关闭业务 HTTP 端口；仅通过独立管理端口 `management.server.port=9081` 暴露 actuator 的 liveness / readiness / metrics。

### 3.5 前端交互设计

前端形态为单页控制台，技术假设 Vue 3 + TypeScript + Vite [Assumption, TBD]；构建产物作为静态资源由 `ncg-web` 托管。可点击原型见 `ncg-frontend-prototype.html`：单文件、无需构建、数据为演示数据，用于确认信息架构、状态与动作，不作为最终视觉稿。

#### 3.5.1 设计原则

| 原则 | 含义 | 实现约束 |
|---|---|---|
| 环境是全局上下文 | 顶栏固定 dev / staging / prod 切换，列表与详情随环境过滤 | 切换环境不改变权限；实例不可用时页面进入只读 |
| 状态只由后端给 | 页面不根据草稿或快照推断“已发布” | 状态标签直接映射 §4.2 状态机；未知状态显示原值 |
| 写操作三段式 | 预检 → 执行 → 读回 | 预检失败时按钮禁用并展示原因，不发出请求 |
| 危险动作二次确认 | 发布、驳回、恢复覆盖、取消定时、禁用账号 | 驳回与敏感值查看必须填写原因 |
| 不确定不乐观 | `indeterminate` 不显示“重试” | 只显示“进入对账”，并持续展示告警 |
| 敏感值默认脱敏 | 明文不落前端缓存 | 查看需权限 + 原因 + 审计，30 秒后重新脱敏 |
| 权限驱动渲染 | 无权限的动作不渲染 | 前端隐藏不等于授权；后端仍 fail-closed |
| 错误可定位 | 使用 §6.1 错误体 | `code` 决定文案与动作，`traceId` 可复制 |
| DataId 是发布单位 | 一个 DataId 是一个配置文件，文件内包含多个配置项 | 一次发布写整个 DataId 并读回；改多个 key 仍只生成一个发布记录；批量按 DataId 生成子项 |
| 格式由后缀决定 | 按 DataId 后缀识别 PROPERTIES / YAML / JSON / TEXT；`.yml` 与 `.yaml` 同为 YAML | 格式在纳管时固化，不可变更；解析失败定位到行并阻断；不支持的格式只读；写回仍针对整个文件 |

#### 3.5.2 信息架构

| 一级 | 页面 | 路由 | 主要角色 | 关键动作 | 主要接口 |
|---|---|---|---|---|---|
| 治理 | 工作台 | `/workbench` | 全部 | 看待审批、直连告警、最近发布 | `GET /changes`、`GET /releases`、`GET /direct-changes` |
| 治理 | 配置目录 | `/catalog` | 查看、编辑 | 浏览 DataId、查看配置项、多选、按项填值、直接发起变更、批量发起 | `GET /apps/{appId}/configs`、`GET /configs/{mappingId}`、`POST /changes` |
| 治理 | 变更单 | `/changes`、`/changes/:changeNo` | 编辑、审批、发布 | 建单、提交、三方 Diff、影响面 | `/changes`、`/changes/{no}/impact` |
| 治理 | 审批中心 | `/approvals` | 审批人 | 通过、驳回、转交 | `/changes/{no}/approve`、`reject`、`transfer` |
| 治理 | 定时任务 | `/schedules` | 发布人 | 取消、查看复核失败 | `/changes/{no}/schedule` |
| 治理 | 批量变更 | `/batches` | 编辑、发布 | 查看部分成功、重试失败项 | `/batches/{id}/retry` |
| 发布 | 发布控制台 | `/releases` | 发布人 | 查看三态结果、人工对账 | `/releases`、`/releases/{id}/reconcile` |
| 发布 | 跨环境同步 | `/env-sync` | 编辑 | 只读对比、生成目标变更单 | `/env-sync/compare`、`/env-sync/apply` |
| 发布 | 回收站 | `/recycle-bin` | 发布人 | 恢复、处理同名冲突 | `/recycle-bin/{id}/restore` |
| 监督 | 审计与检测 | `/audit` | 审计员 | 查询、导出、校验哈希链、处理直连 | `/audit-events`、`/direct-changes` |
| 管理 | 管理与设置 | `/admin` | 管理员 | 账号授权、实例测试、通知绑定 | `/users`、`/instances/{id}/test`、`/notify-bindings` |

#### 3.5.3 关键交互契约

| 场景 | 触发 | 前端行为 | 阻断与失败 | 依据 |
|---|---|---|---|---|
| 发布预检 | 点击执行发布 | 展示 6 项预检；全部通过才可执行；执行中按钮禁用 | 未通过项标红并给出原因；`REL-409-001` / `REL-412-001` | R-REL-01、AC-08 |
| 发布结果 | 收到发布响应 | `verified` 绿色、`rejected` 红色、`indeterminate` 琥珀色；展示读回 MD5 与 TraceId | `indeterminate` 不出现重试，只出现对账；`REL-502-001` | R-REL-02、R-REL-06、AC-09 |
| 目录内发起变更 | 配置详情点击发起变更 | 按配置项列出 key、当前值与新值输入；一次可改同一 DataId 的多个项；原因至少 10 个字；创建平台草稿并进入变更详情 | 至少修改一项；已有进行中单提示 `CR-409-001`，草稿可存但提交被阻断 | R-CR-01、R-CR-02、AC-03 |
| 多选批量发起 | 勾选多个 DataId 后点击批量发起 | 每个 DataId 一个子项；在弹窗中逐项填写新值；同一 DataId 内多个 key 合并为一个子项；生成父单 + 子项草稿 | 未填任何新值时阻断；超过上限 `BAT-413-001`；同 DataId 冲突 `CR-409-001` | R-SCH-04、R-SCH-05、AC-22 |
| 批量子项编辑值 | 草稿父单点击“编辑值” | 打开该 DataId 的配置项列表，修改后保存并重算 Diff；提交后只读 | 提交后不可改；敏感项需重新输入，不展示旧明文 | R-SCH-04、R-SCH-05、AC-22 |
| 按格式编辑 | 打开配置项列表 | 按 DataId 的 `format` 展示：PROPERTIES 原样 key 逐行、YAML 展示点路径与层级、TEXT 仅全文编辑 | 后缀无法识别时只读，发起变更返回 `CR-415-001` | R-CR-08、R-CR-09 |
| 格式校验失败 | 提交或发布前解析内容 | 返回行号（可得列号），高亮对应配置项，保留草稿不丢失 | 阻断提交，返回 `CR-422-001` | R-CR-07、R-CR-09 |
| 审批守卫 | 打开待审批变更 | 提交人或发布人看到说明与不可用动作，不渲染通过按钮 | 后端返回 `AUTHZ-403-002` | R-AUTH-06、AC-05 |
| 高风险审批 | 高风险变更 | 仅应用负责人或其代理人可见通过动作 | 其他人返回 `AUTHZ-403-003` | R-AUTH-07、AC-06 |
| 驳回 | 点击驳回 | 弹出原因输入，至少 10 个字；计数与错误就地展示 | `APR-422-001` | R-APR-01、AC-05 |
| 基线漂移 | 打开已漂移变更 | 顶部红色横幅 + 基线/草稿/当前三方 Diff；发布入口禁用 | 发布返回 `REL-412-001`，退回待审批 | R-CR-05、AC-04 |
| 敏感值查看 | 点击查看明文 | 原因至少 10 个字；展示后 30 秒重新脱敏；写审计 | 权限不足 `AUTHZ-403-001`；前端不缓存明文 | R-AUTH-09、AC-18 |
| 影响面未知 | 监听查询失败 | 黄色横幅、风险上调一级、影响面显示“未知” | 高风险发布被阻断 `IMP-409-001` | R-IMP-05、AC-17 |
| 跨环境同步 | 勾选差异项并生成 | 只创建目标环境变更单，展示目标单号；源环境只读 | 目标已有进行中单 `SYN-409-001` | R-SYN-03、AC-20、AC-21 |
| 恢复同名冲突 | 点击恢复 | 只给“覆盖并重新审批”或“放弃”两个选项，禁止静默覆盖 | `RB-409-001`；超期 `RB-410-001` | R-RB-04、AC-16 |
| 定时到点失败 | 复核失败 | 任务转“待发布”并告警，不自动补发 | `SCH-409-002` | R-SCH-02、AC-21 |
| 批量部分成功 | 子项失败 | 成功项标“不重发”，失败项可单独重试 | `BAT-413-001` 仅用于超限 | R-SCH-05、AC-22 |

#### 3.5.4 页面状态

| 状态 | 表现 | 约束 |
|---|---|---|
| 加载 | 骨架屏，保持布局稳定 | 不使用全屏 spinner 遮挡上下文 |
| 空态 | 说明为什么为空 + 下一步动作 | 不把“无数据”显示成“正常” |
| 无权限 | 不渲染动作；必要时说明所需权限点 | 不做前端越权兜底 |
| 只读降级 | 顶栏实例红色 + 全局只读条；写按钮全部禁用 | `SYS-423-001`；恢复后不自动补发 |
| 影响面未知 | 黄色横幅 + “未知”标签 | 风险上调，阻断高风险发布 |
| 请求失败 | 错误码 + 可读文案 + TraceId + 建议动作 | 保留用户已填内容，不清空表单 |

#### 3.5.5 视觉系统

| 维度 | 选择 | 理由 |
|---|---|---|
| 基调 | 深色控制台：ink `#0A0C10`、panel `#12151C`、1px 分隔线 `#242A35` | 长时间盯盘不刺眼；层级靠边框与留白，不靠阴影 |
| 强调色 | volt `#C6F24E`；amber `#FFB55C` 警告；red `#FF6B5E` 危险；cyan `#7FD1E8` 信息 | 强调色只用于动作与状态，不用于装饰 |
| 字体 | 标题 Bricolage Grotesque；正文 Instrument Sans；数据 IBM Plex Mono + `tabular-nums` | 数据列对齐；标题有辨识度 |
| 布局 | 64px 图标栏 + 56px 顶栏 + 三栏内容区；圆角 10–14px | 高密度但不拥挤 |
| 表格 | 行高 44px，hover 高亮；状态用“圆点 + 文本 + 颜色” | 扫描效率优先，状态不只靠颜色 |
| 动效 | 180–240ms ease-out；不做循环动画 | 只用于操作反馈，不干扰判断 |

反模式：不用紫色渐变、玻璃拟态与通用 SaaS 卡片墙；不在列表中展示敏感明文；不用颜色单独表达状态。

字体说明：原型通过 Google Fonts 加载并使用 `display=swap`，离线或受限网络回退到系统字体；生产环境应自托管字体文件，避免外部依赖。

#### 3.5.6 可访问性与响应式

- 状态不只靠颜色：颜色 + 文本 + 图标三重编码。
- 键盘可用：`Ctrl/Cmd + K` 打开命令面板，`Esc` 关闭弹窗；弹窗焦点锁定并返回触发元素。
- 点击目标不小于 32px；表格行可点击时同时提供明确操作入口。
- 断点：≥1240px 三栏（目录树 / 列表 / 详情）；980–1240px 两栏；<980px 单栏或抽屉。
- 动效 150–250ms；遵循 `prefers-reduced-motion`。

#### 3.5.7 与后端的契约

- 错误体固定使用 §6.1 结构；前端按 `code` 分支，不解析 `message` 文案。
- 分页使用 `page` / `size`；列表滚动加载不改变筛选条件。
- 发布、对账、批量重试进入终态前可轮询详情接口；终态后停止轮询，避免持续打满。
- 发布按钮在请求进行中禁用；重复提交由后端幂等键“变更单号 + 目标版本”兜底。
- 时间统一按 `Asia/Shanghai` 展示，接口保留 ISO-8601；相对时间仅用于辅助。

## 4 领域模型

### 4.1 聚合与对象

| 聚合 | 根对象 | 内部对象 | 说明 |
|---|---|---|---|
| 配置目录 | `ConfigMapping` | 快照引用 | 唯一 TongNCS 坐标（一个 DataId 文件） |
| 变更 | `ChangeRequest` | 内容、基线、影响面、风险 | 状态机核心 |
| 审批 | `Approval` | 审批意见 | 与变更单关联 |
| 发布 | `ReleaseRecord` | 回执、读回值 | 一次发布尝试 |
| 调度 | `ScheduleJob` | 复核记录 | 定时发布 |
| 批量 | `Batch` | `BatchItem` | 父单 + 子项 |
| 通知 | `Notification` | 发送结果 | 发件箱 |
| 直连 | `DirectChangeEvent` | 匹配结果 | 未授权变更 |
| 回收站 | `RecycleBinItem` | 恢复状态 | 30 天保留 |
| 同步 | `EnvSyncSnapshot` | 差异摘要 | 只读快照 |
| 审计 | `AuditEvent` | 哈希链 | append-only |
| 连接 | `Instance` | 探活结果 | 环境绑定 |
| 授权 | `Grant` | 权限点 | 应用 × 环境 |

### 4.2 状态机

| 对象 | 状态 | 关键迁移 | 不变量 |
|---|---|---|---|
| ChangeRequest | DRAFT / PENDING_APPROVAL / APPROVED / SCHEDULED / RELEASING / SUCCEEDED / FAILED / INDETERMINATE / CANCELLED | submit、approve、reject、schedule、publish、verified、rejected、indeterminate、reconcile、cancel | 仅 RELEASING 可写 TongNCS；同映射最多 1 个非草稿非终态单 |
| ReleaseRecord | CREATED / WRITING / VERIFIED / REJECTED / INDETERMINATE | start、verified、rejected、indeterminate、reconcile | 终态不可改；对账只追加 |
| ScheduleJob | SCHEDULED / TRIGGERED / PRECHECK / DISPATCHED / RETURNED / CANCELLED / DONE | trigger、precheck、dispatch、return、cancel、finish | 同一变更单最多 1 个活动任务 |
| Batch | CREATED / RUNNING / SUCCEEDED / PARTIAL_SUCCESS / FAILED | start、all_success、partial、all_failed、retry_failed | 成功子项不得重发 |
| DirectChangeEvent | DETECTED / MATCHED / UNAUTHORIZED / NOTIFIED / CLOSED / BLIND_SPOT | match、no_match、blind、notify、close | 同一历史记录只报警一次 |
| Notification | QUEUED / SENDING / SENT / RETRYING / DEAD | send、retry、dead | 重试 ≤ 3；失败不回滚业务 |

### 4.3 关键值对象

| 值对象 | 字段 | 用途 |
|---|---|---|
| `ConfigRef` | namespace、group、dataId | 一个 DataId = 一个配置文件，是读取与发布单位 |
| `ConfigFormat` | PROPERTIES / YAML / JSON / TEXT | 由 DataId 后缀识别并在纳管时固化，决定解析、展示与写回方式 |
| `ConfigItem` | key（点路径）、value、valueType、sensitive | DataId 文件内的配置项，是编辑单位 |
| `Snapshot` | content、md5、collectedAt、source | 读取结果 |
| `PublishOutcome` | verified / rejected / indeterminate + receipt + reason | 发布三态 |
| `IdempotencyKey` | changeNo + targetVersion | 发布幂等 |
| `RiskAssessment` | level、factors、unknowns | 风险计算 |
| `MaskedValue` | masked、sensitive | 展示与审计 |

### 4.4 领域服务

- `RiskCalculator`：按环境、敏感级别、变更类型、Diff 规模、订阅实例数、近 7 天失败/回滚、执行时间计算风险；用户只能上调。
- `BaselineGuard`：比较 `baseline_md5` 与当前读值，漂移时阻断。
- `PermissionPolicy`：判断提交人/发布人/审批人是否满足职责分离。
- `HashChain`：计算审计事件哈希。
- `ConfigContentCodec`：按 `ConfigFormat` 解析、校验与序列化配置内容；解析失败返回行号/列号（`CR-422-001`），格式不支持时拒绝写操作（`CR-415-001`）。

### 4.5 配置格式与解析契约

格式由 DataId 后缀在纳管时识别并固化；`.yaml` 与 `.yml` 同为 YAML。所有格式统一解析为配置项集合（编辑单位），但发布仍写整个 DataId 文件。

| 格式 | 后缀 | 解析规则 | 编辑展示 | 写回 |
|---|---|---|---|---|
| PROPERTIES | `.properties` | 按行解析 `key=value` / `key:value`；`#` / `!` 开头为注释；`\` 结尾为续行 | 配置项列表，key 原样 | 按 key=value 逐行写回；注释与顺序保留策略 [Assumption, TBD] |
| YAML | `.yaml` / `.yml` | 层级解析；嵌套 key 展开为点路径，数组下标写作 `[n]` | 配置项列表，展示点路径与实际层级 | 按原结构序列化；注释、锚点、多文档保留策略 [Assumption, TBD] |
| JSON | `.json` | 标准 JSON 对象；嵌套 key 展开规则同 YAML | 配置项列表 | 按原结构序列化 |
| TEXT | 其他后缀 | 不解析，整个文件视为一个内容项 | 仅全文编辑 | 原样写回 |

约束：

- 格式在纳管时固化；修改后缀必须先解除纳管再重新纳管。
- 解析失败阻断提交，返回 `CR-422-001` 与行号；无法识别或不在上表的格式只读，发起变更返回 `CR-415-001`。
- 跨环境同步要求源与目标 `ConfigMapping.format` 一致，否则返回 `SYN-409-002`。
- 敏感项识别基于展开后的 key；脱敏、审计与 30 秒重新脱敏规则对所有格式一致。

## 5 数据库设计

### 5.1 约定

- 单库单 schema `ncg`；表名 `ncg_` 前缀；列名 snake_case。
- 主键统一 `varchar(26)` ULID；时间统一 `timestamptz`，存 UTC。
- 乐观锁列 `version bigint not null default 0`。
- 敏感列 `*_ciphertext`，不存明文。
- 不使用数据库外键级联删除；删除一律软删除或状态标记。

### 5.2 表清单

| 表 | 用途 | 唯一约束 / 关键索引 |
|---|---|---|
| `ncg_account` | 自建账号、密码与状态 | `uk_account_login(login_name)` |
| `ncg_user_group` | 用户组 | `uk_group_code(group_code)` |
| `ncg_group_member` | 组成员 | `uk_group_member(group_id, account_id)` |
| `ncg_app` | 应用与负责人 | `uk_app_code(app_code)` |
| `ncg_env` | 环境、发布窗口与启停状态 | `uk_env_code(env_code)` |
| `ncg_instance` | TongNCS 实例 | `uk_instance_env(env_id)` |
| `ncg_config_mapping` | 配置映射（含配置格式） | `uk_mapping_ref(namespace, group_name, data_id)`；`idx_mapping_app_env(app_id, env_id)` |
| `ncg_grant` | 授权 | `uk_grant(subject_type, subject_id, app_id, env_id, permission)` |
| `ncg_change_request` | 变更单 | `uk_change_no(change_no)`；部分唯一索引 `uk_active_change(mapping_id) where status in (...)` |
| `ncg_approval` | 审批记录（通过 / 驳回 / 转交） | `idx_approval_change(change_no)` |
| `ncg_release_record` | 发布记录 | `uk_release_key(release_key)`；`idx_release_change(change_no)` |
| `ncg_audit_event` | 审计事件 | `uk_audit_chain(chain_date, seq)`；`idx_audit_time(occurred_at)` |
| `ncg_schedule_job` | 定时任务 | `uk_schedule_change(change_no) where status in (...)` |
| `ncg_batch` | 批量父单 | `uk_batch_change(parent_change_no)` |
| `ncg_batch_item` | 批量子项 | `uk_batch_item(batch_id, change_no)` |
| `ncg_notification` | 发件箱 | `idx_notification_status(status, next_retry_at)`；`idx_notification_dedup(dedup_key)` |
| `ncg_notify_binding` | 应用 × 环境通知渠道绑定 | `uk_notify_binding(app_id, env_id, channel)` |
| `ncg_direct_change_event` | 直连事件 | `uk_direct(env_id, data_id, tongncs_history_id)` |
| `ncg_recycle_bin` | 回收站 | `uk_recycle_mapping(mapping_id)` |
| `ncg_env_sync_snapshot` | 同步快照 | `idx_sync_created(created_at)` |
| `ncg_scan_cursor` | 扫描游标 | `pk_scan(env_id)` |
| `ncg_worker_lease` | Worker 租约 | `pk_lease(lease_key)` |
| `ncg_session` | Spring Session | 框架表 |

`ncg_config_mapping.format` 为 `varchar(16) not null`，取值 `PROPERTIES / YAML / JSON / TEXT`；纳管时按 `data_id` 后缀写入并固化，之后不可更新。迁移回填规则：`.properties` → PROPERTIES，`.yaml` / `.yml` → YAML，`.json` → JSON，其余 → TEXT（TEXT 默认只读）。

### 5.3 核心表字段

#### ChangeRequest

| 列 | 类型 | 约束 |
|---|---|---|
| change_no | varchar(24) | PK；`CR-YYYYMMDD-NNNN` |
| app_id / env_id | varchar(26) | 非空 |
| mapping_id | varchar(26) | 非空 |
| change_type | varchar(16) | CREATE/UPDATE/DELETE/RESTORE/ROLLBACK/SYNC |
| title | varchar(120) | 非空 |
| reason | varchar(2000) | 非空，≥10 字 |
| content_ciphertext | bytea | CREATE/UPDATE 必填 |
| content_sha256 | char(64) | 非空 |
| baseline_md5 | char(32) | 提交后不可改 |
| baseline_snapshot_ref | varchar(26) | 非空 |
| impact_snapshot | jsonb | 提交时冻结 |
| risk_level | varchar(8) | LOW/MEDIUM/HIGH |
| risk_factors | jsonb | 非空 |
| status | varchar(24) | 见 4.2 |
| version | bigint | 乐观锁 |
| submitter_id / publisher_id | varchar(26) | publisher 发布前确定 |
| schedule_at | timestamptz | SCHEDULED 必填 |
| trace_id | varchar(32) | 非空 |
| created_at / updated_at / closed_at | timestamptz | closed_at 仅终态 |

#### ReleaseRecord

| 列 | 类型 | 约束 |
|---|---|---|
| release_id | varchar(26) | PK |
| release_key | varchar(80) | 唯一；`change_no + ':' + target_version` |
| change_no | varchar(24) | 非空 |
| mapping_id / env_id | varchar(26) | 非空 |
| source_type | varchar(16) | MANUAL/SCHEDULED/BATCH/ROLLBACK/RESTORE |
| target_version | varchar(64) | 非空 |
| outcome | varchar(16) | WRITING/VERIFIED/REJECTED/INDETERMINATE |
| reject_reason | varchar(16) | BASELINE/AUTH/UNAVAILABLE/INVALID |
| expected_md5 / readback_md5 | char(32) | VERIFIED 时相等 |
| receipt | jsonb | 脱敏回执 |
| error_code | varchar(32) | 失败必填 |
| operator_id / system_actor_id | varchar(26) | 至少一个非空 |
| trace_id | varchar(32) | 非空 |
| started_at / finished_at | timestamptz | 终态写 finished_at |

#### AuditEvent

| 列 | 类型 | 约束 |
|---|---|---|
| event_id | varchar(26) | PK |
| chain_date | date | 按 UTC 日分链 |
| seq | bigint | `uk_audit_chain(chain_date, seq)` |
| occurred_at | timestamptz | 非空 |
| actor_type | varchar(8) | USER/SYSTEM |
| actor_id | varchar(64) | 非空 |
| source_ip | varchar(45) | 用户操作必填 |
| action | varchar(48) | 固定枚举 |
| object_type / object_id | varchar(32) | 非空 |
| before_masked / after_masked | jsonb | 脱敏 |
| result | varchar(8) | SUCCESS/FAILURE |
| error_code | varchar(32) | FAILURE 必填 |
| change_no | varchar(24) | 关联时必填 |
| trace_id | varchar(32) | 非空 |
| prev_hash / event_hash | char(64) | 非空 |

#### ConfigMapping

| 列 | 类型 | 约束 |
|---|---|---|
| mapping_id | varchar(26) | PK |
| app_id / env_id | varchar(26) | 非空 |
| namespace / group_name / data_id | varchar(128) | 组合唯一 |
| format | varchar(16) | PROPERTIES/YAML/JSON/TEXT；纳管时写入，之后不可更新 |
| sensitive | boolean | 默认 false |
| owner_id | varchar(26) | 应用负责人 |
| status | varchar(16) | MANAGED/UNMANAGED/DELETED |
| last_snapshot_md5 | char(32) | 最近读取 |
| last_collected_at | timestamptz | 快照时间 |
| version | bigint | 乐观锁 |

#### Account

| 列 | 类型 | 约束 |
|---|---|---|
| account_id | varchar(26) | PK |
| login_name | varchar(64) | 唯一，非空 |
| display_name | varchar(64) | 非空 |
| password_hash | varchar(255) | Argon2id，非空 |
| status | varchar(16) | ENABLED / DISABLED / FROZEN |
| failed_attempts | int | 默认 0 |
| locked_until | timestamptz | 可空；锁定期内拒绝登录 |
| is_admin | boolean | 默认 false |
| last_login_at | timestamptz | 可空 |
| version | bigint | 乐观锁 |

#### App

| 列 | 类型 | 约束 |
|---|---|---|
| app_id | varchar(26) | PK |
| app_code | varchar(64) | 唯一，非空 |
| name | varchar(120) | 非空 |
| owner_id | varchar(26) | 应用负责人，非空 |
| description | varchar(500) | 可空 |
| status | varchar(16) | ACTIVE / ARCHIVED |
| version | bigint | 乐观锁 |

#### Env

| 列 | 类型 | 约束 |
|---|---|---|
| env_id | varchar(26) | PK |
| env_code | varchar(16) | 唯一；dev / staging / prod |
| display_name | varchar(64) | 非空 |
| sort_order | int | 非空 |
| release_window_start | time | 可空；为空表示全天 |
| release_window_end | time | 可空；与 start 同时为空或同时非空 |
| status | varchar(16) | ENABLED / DISABLED |
| version | bigint | 乐观锁 |

#### UserGroup

| 列 | 类型 | 约束 |
|---|---|---|
| group_id | varchar(26) | PK |
| group_code | varchar(64) | 唯一，非空 |
| group_name | varchar(64) | 非空 |
| description | varchar(500) | 可空 |
| created_by | varchar(26) | 非空 |
| created_at | timestamptz | 非空 |

#### GroupMember

| 列 | 类型 | 约束 |
|---|---|---|
| group_id | varchar(26) | 非空 |
| account_id | varchar(26) | 非空 |
| added_by | varchar(26) | 非空 |
| added_at | timestamptz | 非空 |
| — | — | `uk_group_member(group_id, account_id)` |

#### Approval

| 列 | 类型 | 约束 |
|---|---|---|
| approval_id | varchar(26) | PK |
| change_no | varchar(24) | 非空 |
| actor_id | varchar(26) | 非空 |
| action | varchar(16) | APPROVE / REJECT / TRANSFER |
| reason | varchar(2000) | REJECT 必填且 ≥10 字 |
| target_approver_id | varchar(26) | TRANSFER 必填 |
| trace_id | varchar(32) | 非空 |
| created_at | timestamptz | 非空 |

#### NotifyBinding

| 列 | 类型 | 约束 |
|---|---|---|
| binding_id | varchar(26) | PK |
| app_id / env_id | varchar(26) | 非空 |
| channel | varchar(16) | IN_APP / WECOM |
| target_ciphertext | bytea | Webhook 密文；IN_APP 可空 |
| status | varchar(16) | ENABLED / DISABLED |
| version | bigint | 乐观锁 |
| — | — | `uk_notify_binding(app_id, env_id, channel)` |

#### ScanCursor

| 列 | 类型 | 约束 |
|---|---|---|
| env_id | varchar(26) | PK |
| last_history_id | varchar(128) | 非空 |
| last_scan_at | timestamptz | 非空 |
| blind_spot_since | timestamptz | 可空；发现历史缺口时写入 |
| consecutive_failures | int | 默认 0 |

#### WorkerLease

| 列 | 类型 | 约束 |
|---|---|---|
| lease_key | varchar(64) | PK |
| owner_id | varchar(128) | 非空 |
| expires_at | timestamptz | 非空 |
| heartbeat_at | timestamptz | 非空 |
| version | bigint | 乐观锁 |

### 5.4 并发与唯一约束

| 场景 | 机制 | 冲突行为 |
|---|---|---|
| 发布 | `uk_release_key` | 返回既有记录，不再写 TongNCS |
| 建单 | 部分唯一索引 `mapping_id` + 活跃状态 | 返回 CR-409-001 |
| 映射 | `uk_mapping_ref` | 返回 CAT-409-001 |
| 实例 | `uk_instance_env` | 返回 CON-409-001 |
| 授权 | `uk_grant` | 重复授权幂等 |
| 直连 | `uk_direct` | 不重复报警 |
| 状态迁移 | `WHERE status=? AND version=?` | 返回 SYS-409-001 |
| 定时任务 | `lease_owner + lease_expires_at` | 过期可接管 |
| 批量子项 | `uk_batch_item` | 成功项不重发 |

### 5.5 审计哈希链

- `chain_date` 为 UTC 日期，`seq` 当日从 1 递增。
- `prev_hash`：当天首条为 64 个 0；否则为上一条 `event_hash`。
- `event_hash = SHA-256(prev_hash + "|" + canonical_json(event))`。
- `canonical_json` 固定字段顺序，排除 `event_hash` 自身。
- 数据库账号只授予 `INSERT/SELECT`；触发器拒绝 `UPDATE/DELETE`。
- 每日导出校验清单，附日链首尾哈希。

### 5.6 保留与清理

| 数据 | 保留 | 清理方式 |
|---|---|---|
| 审计 | 永久 | 不删除；冷归档方案见 A-07 |
| 回收站 | 30 天 | 到期清除并写审计 |
| 通知 | 180 天 [Assumption, TBD] | 归档或删除 |
| 同步快照 | 90 天 [Assumption, TBD] | 到期删除 |
| 会话 | 30 分钟空闲 | 框架清理 |

## 6 接口设计

### 6.1 通用约定

- Base path：`/api/v1`；JSON；UTF-8。
- 认证：`NCG_SESSION` Cookie；CSRF：`X-XSRF-TOKEN` 请求头。
- TraceId：请求头 `X-Trace-Id` 可传入；未传则生成，响应回写。
- 分页：`page`（从 0 开始）、`size`（默认 20，最大 100）。
- 错误体：

```json
{
  "code": "REL-412-001",
  "message": "基线已变化，请重新审批",
  "traceId": "7f3c...",
  "details": { "changeNo": "CR-20261121-0001" },
  "timestamp": "2026-11-21T10:00:00Z"
}
```

### 6.2 接口清单

| 方法 | 路径 | 权限 | 幂等 | 主要错误 |
|---|---|---|---|---|
| POST | `/auth/login` | 匿名 | 否 | AUTH-401-001、AUTH-401-002 |
| POST | `/auth/logout` | 已登录 | 是 | AUTH-401-001 |
| GET | `/auth/me` | 已登录 | 是 | AUTH-401-001 |
| GET | `/apps` | VIEW | 是 | — |
| GET | `/apps/{appId}/configs` | VIEW | 是 | SYS-404-001 |
| GET | `/configs/{mappingId}` | VIEW | 是 | SYS-404-001、CON-502-001 |
| POST | `/configs/{mappingId}/sensitive-view`（body: `itemKey`） | VIEW_SENSITIVE | 否 | AUTHZ-403-001、CR-400-001、SYS-404-001 |
| POST | `/mappings` | ADMIN | 否 | CAT-409-001 |
| PUT | `/mappings/{id}` | ADMIN | 否 | CAT-409-001、SYS-409-001 |
| DELETE | `/mappings/{id}` | ADMIN | 否 | SYS-404-001 |
| POST | `/mappings/import` | ADMIN | 否 | CAT-409-002 |
| POST | `/changes` | EDIT | 否 | CR-400-001、CR-409-001、CR-415-001、CR-422-001 |
| PUT | `/changes/{changeNo}` | EDIT | 否 | CR-400-001、CR-415-001、CR-422-001、SYS-409-001 |
| POST | `/changes/{changeNo}/submit` | SUBMIT | 否 | CR-412-001、CR-413-001 |
| POST | `/changes/{changeNo}/cancel` | SUBMIT/PUBLISH | 是 | SYS-409-001 |
| GET | `/changes` | VIEW | 是 | — |
| GET | `/changes/{changeNo}` | VIEW | 是 | SYS-404-001 |
| GET | `/changes/{changeNo}/impact` | VIEW | 是 | IMP-409-001 |
| POST | `/changes/{changeNo}/approve` | APPROVE | 否 | AUTHZ-403-002、AUTHZ-403-003、APR-409-001 |
| POST | `/changes/{changeNo}/reject` | APPROVE | 否 | APR-422-001 |
| POST | `/changes/{changeNo}/transfer` | APPROVE | 否 | AUTHZ-403-001 |
| POST | `/changes/{changeNo}/release` | PUBLISH | 是 | REL-403-001、REL-409-001、REL-412-001、REL-422-001、REL-502-001、REL-503-001 |
| POST | `/changes/{changeNo}/rollback` | ROLLBACK | 是 | RB-410-001、REL-403-001、REL-502-001、REL-503-001 |
| POST | `/changes/{changeNo}/schedule` | PUBLISH | 否 | SCH-409-001 |
| DELETE | `/changes/{changeNo}/schedule` | PUBLISH | 是 | SCH-409-001 |
| POST | `/releases/{releaseId}/reconcile` | PUBLISH | 是 | REL-502-001 |
| GET | `/releases` | VIEW | 是 | — |
| GET | `/releases/{releaseId}` | VIEW | 是 | SYS-404-001 |
| POST | `/batches` | EDIT | 否 | CR-400-001、CR-409-001、CR-415-001、CR-422-001、BAT-413-001 |
| PUT | `/batches/{batchId}/items/{itemId}` | EDIT | 否 | CR-400-001、CR-415-001、CR-422-001、SYS-409-001 |
| POST | `/batches/{batchId}/submit` | SUBMIT | 否 | BAT-413-001 |
| POST | `/batches/{batchId}/retry` | PUBLISH | 是 | SYS-409-001 |
| POST | `/env-sync/compare` | VIEW | 是 | SYN-409-002 |
| POST | `/env-sync/apply` | EDIT | 否 | SYN-409-001、SYN-409-002 |
| GET | `/env-sync/{snapshotId}` | VIEW | 是 | SYS-404-001 |
| GET | `/instances` | ADMIN | 是 | — |
| POST | `/instances/{id}/test` | ADMIN | 是 | CON-502-001 |
| GET | `/notify-bindings` | ADMIN | 是 | — |
| PUT | `/notify-bindings/{appId}/{envId}` | ADMIN | 否 | CR-400-001、SYS-404-001 |
| DELETE | `/notify-bindings/{appId}/{envId}` | ADMIN | 是 | SYS-404-001 |
| GET | `/audit-events` | VIEW_AUDIT | 是 | — |
| POST | `/audit-events/export` | EXPORT_AUDIT | 否 | AUD-500-001 |
| GET | `/direct-changes` | VIEW_AUDIT | 是 | — |
| POST | `/direct-changes/{id}/close` | VIEW_AUDIT | 是 | SYS-404-001 |
| GET | `/recycle-bin` | PUBLISH | 是 | — |
| POST | `/recycle-bin/{id}/restore` | PUBLISH | 是 | RB-409-001、RB-410-001、REL-503-001 |
| GET | `/users` / `/grants` | ADMIN | 是 | — |
| POST | `/users` / `/grants` | ADMIN | 否 | AUTHZ-403-001 |

### 6.3 关键请求示例

创建变更单（结构化格式默认按配置项提交；`TEXT` 或整文件替换才使用 `content`，两者互斥）：

```json
{
  "appId": "01J...",
  "envId": "01J...",
  "mappingId": "01J...",
  "changeType": "UPDATE",
  "title": "调整订单超时时间",
  "reason": "大促期间订单处理时间延长，需要同步调整超时配置",
  "items": [
    { "key": "order.timeout", "value": "180", "valueType": "NUMBER" },
    { "key": "order.retry", "value": "3", "valueType": "NUMBER" }
  ],
  "baselineMd5": "9e107d9d372bb6826bd81d3542a419d6",
  "plannedAt": "2026-11-22T02:00:00Z"
}
```

响应：

```json
{ "changeNo": "CR-20261121-0001", "status": "DRAFT", "riskLevel": "MEDIUM", "version": 0, "format": "YAML", "itemCount": 2 }
```

- `items` 与 `content` 必须二选一：`UPDATE` 的结构化格式用 `items`，后端通过 `ConfigContentCodec` 读取当前 DataId、应用变更并序列化；`CREATE`、`TEXT` 或整文件替换用 `content`。
- `items[].key` 使用展开后的点路径；`valueType` 取 `STRING / NUMBER / BOOLEAN / JSON`，缺省按 `STRING`。
- `baselineMd5` 在创建时记录，提交时复核；不一致返回 `CR-412-001`。
- 解析失败返回 `CR-422-001` 与行号；格式不支持返回 `CR-415-001`。

查询配置详情（敏感值默认脱敏）：

```json
{
  "mappingId": "01J...",
  "dataId": "payment-datasource.properties",
  "format": "PROPERTIES",
  "md5": "3ad9c0f1...",
  "collectedAt": "2026-11-22T08:15:00Z",
  "items": [
    { "key": "spring.datasource.url", "value": "jdbc:mysql://pay-db:3306/pay", "valueType": "STRING", "sensitive": false },
    { "key": "spring.datasource.password", "value": "••••••", "valueType": "STRING", "sensitive": true }
  ]
}
```

- `TEXT` 格式返回 `content`，不返回 `items`。
- 内容无法解析时仍返回 200，`items` 为空并附 `parseError: { line, column, message }`；前端只读展示，不阻断浏览。

查看敏感项明文（按 `itemKey`，需 `VIEW_SENSITIVE`，写审计）：

```json
{ "itemKey": "spring.datasource.password", "reason": "排查连接失败，需核对密码" }
```

```json
{ "itemKey": "spring.datasource.password", "value": "P@ssw0rd_pay_2026", "expiresAt": "2026-11-22T08:16:30Z" }
```

批量创建（每个 DataId 一个子项，子项内按配置项填值）：

```json
{
  "title": "大促前批量调整支付参数",
  "reason": "大促容量与日志级别统一调整",
  "items": [
    {
      "mappingId": "01J...",
      "items": [
        { "key": "payment.circuit.threshold", "value": "25", "valueType": "NUMBER" },
        { "key": "payment.circuit.enabled", "value": "true", "valueType": "BOOLEAN" }
      ]
    },
    {
      "mappingId": "01J...",
      "items": [
        { "key": "log.level", "value": "DEBUG", "valueType": "STRING" }
      ]
    }
  ]
}
```

```json
{ "batchId": "01J...", "childCount": 2, "status": "DRAFT" }
```

- 每个 DataId 生成一个 `ChangeRequest` 子草稿；任一所选 DataId 已有进行中变更单时整批拒绝，返回 `CR-409-001`。
- 未填任何新值返回 `CR-400-001`；超过上限返回 `BAT-413-001`。

草稿阶段编辑批量子项的值（提交后只读）：

```http
PUT /batches/{batchId}/items/{itemId}
```

```json
{ "version": 3, "items": [ { "key": "payment.circuit.threshold", "value": "30", "valueType": "NUMBER" } ] }
```

```json
{ "itemId": "01J...", "status": "DRAFT", "version": 4, "itemCount": 2 }
```

- 子项或父单已提交时返回 `SYS-409-001`；解析失败 `CR-422-001`；格式不支持 `CR-415-001`。
- 修改后重算该子项的 `content_ciphertext`、`content_sha256` 与 Diff；敏感项必须重新输入新值，不回显旧明文。

发布请求与响应：

```json
{ "targetVersion": "1.0.3", "expectedMd5": "9e107d9d372bb6826bd81d3542a419d6" }
```

```json
{
  "releaseId": "01J...",
  "outcome": "VERIFIED",
  "expectedMd5": "9e10...",
  "readbackMd5": "9e10...",
  "finishedAt": "2026-11-21T10:00:05Z"
}
```

### 6.4 错误码映射

| 域前缀 | HTTP 范围 | 说明 |
|---|---|---|
| AUTH / AUTHZ | 401 / 403 | 登录、权限、职责分离 |
| CAT / CR / APR | 400 / 409 / 412 / 413 / 422 | 目录、变更单、审批 |
| REL | 403 / 409 / 412 / 422 / 502 / 503 | 发布与适配器 |
| RB / SYN / SCH / BAT | 400 / 409 / 410 / 413 / 415 / 422 | 恢复、同步、定时、批量 |
| AUD / NTF / CON | 500 / 502 / 503 | 审计、通知、连接 |
| SYS | 403 / 404 / 409 / 423 / 500 | 横切与降级 |

完整 39 条错误码以 spec E.3 为准；接口层只做映射，不新增编码。

### 6.5 幂等

- 发布：`Idempotency-Key` 可省略；以 `release_key` 为准。重复请求返回 200 与既有发布记录。
- 恢复：`item_id + target_version`。
- 审批/驳回：条件更新，重复提交返回当前状态，不重复写审批记录。
- 扫描与通知：数据库唯一键保证。

### 6.6 错误码到入口的完整映射

> 编码与 HTTP 状态以 spec E.3 为准；本节只做入口分配，不新增、不改写编码。

| 错误码 | 入口 | 返回点 | 备注 |
|---|---|---|---|
| AUTH-401-001 | 登录与全局鉴权 | `/auth/*`、拦截器 | 未登录或会话失效 |
| AUTH-401-002 | 登录与全局鉴权 | `/auth/login`、拦截器 | 账号被禁用或冻结 |
| AUTHZ-403-001 | 所有受控接口 | 鉴权链 | 缺少权限点 |
| AUTHZ-403-002 | `/changes/{changeNo}/approve` | 职责分离守卫 | 提交人或发布人审批冲突 |
| AUTHZ-403-003 | `/changes/{changeNo}/approve` | 高风险守卫 | 审批人非应用负责人 |
| CAT-409-001 | `POST/PUT /mappings` | 唯一约束 | 配置已被其他应用或环境映射 |
| CAT-409-002 | `POST /mappings/import` | 纳管校验 | 纳管动作与现有映射冲突 |
| CR-400-001 | `POST/PUT /changes`、`POST /batches`、`PUT /batches/{batchId}/items/{itemId}` | 参数校验 | 必填缺失或格式错误 |
| CR-409-001 | `POST /changes`、`POST /batches`、`/changes/{changeNo}/submit` | 活跃单唯一索引 | 同配置已有进行中变更单 |
| CR-412-001 | `/changes/{changeNo}/submit` | 基线比对 | 提交前基线漂移 |
| CR-413-001 | `/changes/{changeNo}/submit` | 内容长度校验 | 内容超过上限 |
| CR-415-001 | `POST/PUT /changes`、`POST /batches`、`PUT /batches/{batchId}/items/{itemId}` | 格式识别 | DataId 后缀无法识别或格式不支持，只读 |
| CR-422-001 | 同上 | 内容解析 | 解析失败，响应附行号/列号 |
| APR-409-001 | approve / reject / transfer | 状态守卫 | 变更单状态不允许审批 |
| APR-422-001 | `/changes/{changeNo}/reject` | 理由校验 | 驳回缺少理由 |
| REL-403-001 | release / rollback | 发布权限校验 | 无发布权限 |
| REL-409-001 | `/changes/{changeNo}/release` | 状态守卫 | 变更单状态不允许发布 |
| REL-409-002 | `/changes/{changeNo}/release` | 幂等命中 | 返回 200 与既有发布记录 |
| REL-412-001 | `/changes/{changeNo}/release` | 发布前基线比对 | 发布前基线不一致 |
| REL-422-001 | `/changes/{changeNo}/release` | 发布窗口校验 | 发布窗口外 |
| REL-502-001 | release / reconcile | 适配器三态 | 写入结果不确定 |
| REL-503-001 | release / rollback / restore | 实例状态校验 | 实例不可用或连接失败 |
| RB-409-001 | `/recycle-bin/{id}/restore` | 同名冲突校验 | 恢复时同名冲突 |
| RB-410-001 | restore / rollback | 保留期校验 | 超过 30 天不可恢复 |
| AUD-500-001 | 任何写操作 | 审计写入 | 审计失败，整笔业务回滚 |
| IMP-409-001 | `GET /changes/{changeNo}/impact`、release | 影响面守卫 | 影响面缺失阻断高风险发布 |
| NTF-502-001 | 通知发送任务 | Worker 内部 | 通知渠道失败，不对用户返回 |
| CON-409-001 | `POST/PUT /instances` | 实例唯一约束 | 实例已被其他环境绑定 |
| CON-502-001 | `/instances/{id}/test`、`GET /configs/{id}` | 连接测试 | 连接测试失败 |
| SYN-409-001 | `/env-sync/apply` | 目标活跃单校验 | 目标环境已有进行中变更单 |
| SYN-409-002 | `/env-sync/compare`、`/env-sync/apply` | 映射与格式校验 | 跨环境映射或格式不一致 |
| SCH-409-001 | schedule / delete schedule | 任务状态守卫 | 定时任务状态不允许操作 |
| SCH-409-002 | 定时发布任务 | Worker 到点复核 | 复核失败，任务转 RETURNED |
| BAT-413-001 | `POST /batches`、`/batches/{batchId}/submit` | 数量校验 | 批量超过上限 |
| SYS-403-001 | 全局 | 路由与权限 | 不支持紧急发布，无替代通道 |
| SYS-423-001 | 环境只读时的写接口 | 实例状态校验 | 环境只读降级 |
| SYS-404-001 | 资源读取与操作 | 资源查找 | 对象不存在或已删除 |
| SYS-409-001 | 状态迁移接口 | 乐观锁条件更新 | 乐观锁冲突 |
| SYS-500-001 | 全局 | 兜底异常处理 | 未分类内部错误 |

## 7 核心流程

### 7.1 建单与提交

1. `POST /changes`：校验权限、必填、`items` / `content` 二选一与格式；保存 DRAFT；写审计。
2. 计算影响面与风险；草稿阶段可刷新。
3. `POST /submit`：
   - 事务 A：锁定变更单，校验状态与唯一活跃单；读取 TongNCS 当前值（事务外先读，事务内比对）；冻结 `baseline_md5`、快照、影响面、风险；状态转 PENDING_APPROVAL；写审计；写审批通知到发件箱。
   - 提交后任何 TongNCS 变化不回写快照。

### 7.2 审批

1. 加载变更单与审批人资格；校验非提交人、非发布人（应用负责人例外）。
2. 高风险仅应用负责人或代理人可审批。
3. 事务：`UPDATE ... WHERE status='PENDING_APPROVAL' AND version=?`；插入 `ncg_approval`；写审计；写通知发件箱。
4. 驳回必填理由，状态回 DRAFT；通过转 APPROVED。

### 7.3 发布（verified）

1. 预检：状态、基线、窗口、实例、服务账号、发布人权限。
2. 事务 A：状态转 RELEASING；插入 `ReleaseRecord(WRITING)`；写“发布意图”审计。提交。
3. 事务外：`adapter.publish(ref, content, expectedMd5, key)`；适配器写入并读回。
4. 事务 B：写 `outcome`、`readback_md5`、`finished_at`；变更单转 SUCCEEDED/FAILED/INDETERMINATE；写“发布结果”审计；写通知发件箱。
5. 任一预检失败：不进入 RELEASING，返回对应错误码。

### 7.4 不确定与对账

1. 适配器超时或读回失败 → `INDETERMINATE`。
2. 变更单转 INDETERMINATE；高优告警；不自动重试。
3. 人工对账：读 TongNCS 当前值与 MD5，提交证据。
4. `POST /releases/{id}/reconcile`：确认生效 → SUCCEEDED；确认未生效 → FAILED；追加对账审计，不改历史字段。

### 7.5 基线漂移

1. 发布前读取当前 MD5。
2. 与 `baseline_md5` 不一致：状态退回 PENDING_APPROVAL；记录三方 Diff；写审计；通知提交人与审批人。
3. 重新审批后才能再次发布。

### 7.6 回滚

1. 目标为上一已发布版本：发布者可直接执行，填写原因。
2. 目标为更早版本或 TongNCS 历史版本：创建回滚变更单并审批。
3. 回滚与发布共用 Release 流程；生成新 ReleaseRecord，不覆盖历史。

### 7.7 删除与恢复

1. 删除类变更发布成功后，映射置 DELETED，写入 `ncg_recycle_bin`，`purge_at = deleted_at + 30 天`。
2. 恢复：校验 30 天与同名冲突；同名存在时必须选择“覆盖并重新审批”或放弃。
3. 恢复走 Release 写 TongNCS 并读回；生成新发布记录。
4. 到期任务清除回收站项并写审计。

### 7.8 跨环境同步

1. 对比：分别读取 dev/staging/prod 当前值，按逻辑配置聚合；输出仅源存在、仅目标存在、内容不同。
2. 同步：只创建目标环境变更单；不写目标 TongNCS。
3. 目标存在活跃变更单或映射缺失时阻断。
4. 记录源环境、源版本、快照与关联变更单。

### 7.9 定时发布

1. 审批通过后创建 `ScheduleJob`；同一变更单只允许一个活动任务。
2. Worker 到点抢占租约，执行六项复核。
3. 复核失败：任务 RETURNED，变更单回 APPROVED，告警，不写 TongNCS。
4. 复核通过：调 Release 发布；系统账号代执行，审计记录系统账号与原发布人。

### 7.10 批量发布

1. 创建父单与子项；每项独立 Diff、影响面与风险；按最高风险审批。
2. 批量由 Worker 异步执行，Web 只创建批次并返回 202；逐项发布，每项独立事务与发布记录，成功项不重发。
3. 部分失败：父单 PARTIAL_SUCCESS；成功项不重发；失败项可单独重试。
4. 单次上限 50 项 [Assumption, TBD]，超限返回 BAT-413-001。

### 7.11 直连检测

1. Worker 每 5 分钟按环境抢占 `scan:{env}` 租约。
2. 从 `ncg_scan_cursor` 读取上次历史 ID，调用 `history` 拉取增量。
3. 对每条历史：匹配平台发布记录（环境 + dataId + 历史 ID / 时间窗 + MD5）。
4. 未匹配：写 `DirectChangeEvent(UNAUTHORIZED)`、审计与通知发件箱。
5. 历史不可用或缺口：写 BLIND_SPOT，不推断“无变更”。
6. 更新游标；唯一键防止重复报警。

### 7.12 通知发件箱

1. 业务事务内写 `ncg_notification(QUEUED)`。
2. Worker 轮询 `QUEUED/RETRYING`，`FOR UPDATE SKIP LOCKED` 领取。
3. 发送成功 → SENT；失败 → RETRYING（1/5/15 分钟）；3 次后 DEAD 并写审计。
4. 同一 `dedup_key` 在 5 分钟窗口内聚合。
5. 通知失败不回滚业务。

## 8 TongNCS 适配器

### 8.1 端口契约

```java
public interface TongNcsConfigPort {
    Snapshot read(ConfigRef ref);
    PublishOutcome publish(ConfigRef ref, String content,
                           String expectedMd5, IdempotencyKey key);
    HistoryPage history(ConfigRef ref, Instant since, String cursor);
    SubscriberSnapshot subscribers(ConfigRef ref);
    ConnectionReport test(EnvId envId);
}
```

`PublishOutcome` 为密封类型，只有 `verified / rejected / indeterminate` 三种；`rejected.reason` 限定 `baseline / auth / unavailable / invalid`。

### 8.2 异常映射

| 情况 | 结果 |
|---|---|
| 写入成功且读回 MD5 一致 | verified |
| 基线不一致 | rejected(baseline) |
| 认证失败 | rejected(auth) |
| 实例不可达 | rejected(unavailable) |
| 内容格式错误 | rejected(invalid) |
| 写入已发出但超时/读回失败 | indeterminate |

### 8.3 连接与认证

- 每环境独立 endpoint 与服务账号；TLS 必选。
- 凭证只存密文；运行期从密钥配置读取。
- 请求头带 TraceId；不把用户个人凭证传给 TongNCS。

### 8.4 超时与重试

| 操作 | 超时 | 重试 |
|---|---|---|
| 连接 | 5 秒 | 否 |
| read / history / subscribers | 10 秒 | 最多 1 次（只读） |
| publish 写入 | 10 秒 | 不自动重试 |
| 读回 | 5 秒 | 不自动重试；失败转 indeterminate |
| test | 10 秒 | 否 |

### 8.5 读回与 MD5

- 写入前计算内容 MD5；写入后立即读取同一坐标并计算 MD5。
- 只有两者相等才返回 verified；读回内容不落平台库，只存 MD5 与脱敏回执。

### 8.6 契约测试

同一套测试跑假件与真实沙箱，覆盖：成功、基线冲突、认证失败、实例不可达、超时、格式错误、历史缺口、订阅者为空、订阅者映射缺失。假件与真实实现必须返回同构结果。

## 9 身份与权限

### 9.1 账号与会话

- 自建账号；密码 Argon2id；最小 12 位 [Assumption, TBD]。
- 登录失败 5 次锁定 15 分钟 [Assumption, TBD]。
- Spring Session JDBC；Cookie `NCG_SESSION`，HttpOnly、Secure、SameSite=Lax；空闲 30 分钟 [Assumption, TBD]。
- CSRF Token 存 Cookie，前端放请求头；写接口全部校验。

### 9.2 权限模型

10 个权限点：`VIEW / EDIT / SUBMIT / APPROVE / PUBLISH / ROLLBACK / VIEW_SENSITIVE / VIEW_AUDIT / EXPORT_AUDIT / ADMIN`。

授权范围为 `app_id × env_id`；支持用户、用户组、角色模板；不授予 TongNCS 全局权限。

### 9.3 鉴权链

1. 会话认证 → 2. 权限点 → 3. 资源范围 → 4. 职责分离 → 5. 实例状态。

任何一步异常按拒绝处理；不以“查询失败”为由放行。

### 9.4 职责分离

- 提交人 ≠ 审批人。
- 发布人 ≠ 审批人；例外：发布人是应用负责人。
- 高风险仅应用负责人或审计过的代理人可审批。
- 单人会签；无审批超时。

### 9.5 敏感值

- 存储：AES-256-GCM；密文格式 `enc:v1:{keyId}:{base64(iv|ciphertext|tag)}`。
- 查看：需 `VIEW_SENSITIVE`，填写原因（≥10 字），展示后写审计。
- 展示：列表与导出统一脱敏；明文只经 `POST /configs/{mappingId}/sensitive-view` 返回，响应 `Cache-Control: no-store`，审计记录原因与对象。

### 9.6 账号禁用

禁用后立即失效；名下未完成变更单转交应用负责人；所有动作写审计。

## 10 审计

### 10.1 事件模型

固定字段：事件 ID、时间、操作人类型/ID、来源 IP、动作、对象类型/ID、变更前后（脱敏）、结果、错误码、变更单号、TraceId、前后哈希。

### 10.2 写入路径

- 业务事务内调用 `AuditWriter.append(event)`；失败抛异常，业务回滚。
- 外部写入分两段：发布意图（提交前）、发布结果（读回后）。
- 导出行为本身写审计。

### 10.3 查询与导出

- 查询：按时间、操作人、对象、变更单、结果过滤；分页。
- 导出：CSV 流式输出，上限 10 万条 [Assumption, TBD]；文件尾附 SHA-256 与条数。
- 导出需 `EXPORT_AUDIT`；导出内容脱敏。

### 10.4 防篡改

- 数据库账号仅 INSERT/SELECT；触发器拒绝 UPDATE/DELETE。
- 每日链首尾哈希单独留存；校验任务按日验证。
- 发现断链立即告警，不自动修复。

### 10.5 审计动作枚举

固定 37 个动作，新增需版本化：

- 账号与授权：`LOGIN_SUCCESS`、`LOGIN_FAILURE`、`LOGOUT`、`ACCOUNT_CREATE`、`ACCOUNT_DISABLE`、`ACCOUNT_ENABLE`、`GRANT_CHANGE`。
- 目录：`MAPPING_CREATE`、`MAPPING_UPDATE`、`MAPPING_DELETE`、`MAPPING_IMPORT`、`CONFIG_SENSITIVE_VIEW`。
- 变更与审批：`CHANGE_CREATE`、`CHANGE_UPDATE`、`CHANGE_SUBMIT`、`CHANGE_CANCEL`、`APPROVAL_APPROVE`、`APPROVAL_REJECT`、`APPROVAL_TRANSFER`。
- 发布：`RELEASE_INTENT`、`RELEASE_RESULT`、`RELEASE_RECONCILE`、`ROLLBACK`、`RESTORE`、`RECYCLE_PURGE`。
- 检测与调度：`DIRECT_CHANGE_DETECTED`、`DIRECT_CHANGE_CLOSED`、`SCHEDULE_CREATE`、`SCHEDULE_CANCEL`、`SCHEDULE_RECHECK_FAIL`、`BATCH_CREATE`、`BATCH_ITEM_RETRY`。
- 运维：`INSTANCE_TEST`、`INSTANCE_STATUS_CHANGE`、`NOTIFY_DEAD`、`AUDIT_EXPORT`、`AUDIT_CHAIN_VERIFY`。

## 11 通知

### 11.1 渠道与绑定

- 站内通知 + 企业微信群机器人。
- 按应用 × 环境绑定群，绑定数据存 `ncg_notify_binding`；未绑定时回落到 `NCG_WECOM_WEBHOOK_DEFAULT`，无默认群则只发站内并写审计。
- 直连变更、发布失败、定时失败 @ 应用负责人。

### 11.2 发件箱

- `ncg_notification` 即发件箱；状态 QUEUED/SENDING/SENT/RETRYING/DEAD。
- 与业务同事务写入；Worker 异步发送。
- 用 `FOR UPDATE SKIP LOCKED` 领取，避免重复发送。

### 11.3 重试与聚合

- 重试 3 次，间隔 1/5/15 分钟 [Assumption, TBD]。
- 同一 `dedup_key` 5 分钟窗口内聚合 [Assumption, TBD]。
- 最终失败写审计并通知平台管理员，不回滚业务。

### 11.4 内容

- 只含对象、动作、结果、操作人、链接。
- 敏感值脱敏；不发送配置原文。

### 11.5 通知事件类型枚举

`CHANGE_SUBMITTED`、`CHANGE_APPROVED`、`CHANGE_REJECTED`、`RELEASE_FAILED`、`RELEASE_INDETERMINATE`、`SCHEDULE_RECHECK_FAILED`、`DIRECT_CHANGE_UNAUTHORIZED`、`BATCH_PARTIAL_FAILED`、`ACCOUNT_DISABLED`、`AUDIT_CHAIN_BROKEN`、`NOTIFY_DEAD`；新增需版本化。

## 12 Worker 与调度

### 12.1 单活与租约

- `ncg_worker_lease` 保存 `worker:active`；TTL 30 秒，每 10 秒续租。
- 备节点轮询；租约过期后可接管。
- 任务级租约：`ScheduleJob.lease_owner/lease_expires_at`；扫描 `scan:{env}` 5 分钟。

### 12.2 任务类型

| 任务 | 频率 | 幂等键 |
|---|---|---|
| 定时发布 | 到点 | change_no + target_version |
| 批量执行 | 事件驱动 + 10 秒兜底轮询 | batch_id + item.change_no |
| 直连扫描 | 5 分钟 | env + data_id + history_id |
| 通知发送 | 10 秒轮询 | dedup_key |
| 回收站清理 | 每日 | item_id |
| 审计链校验 | 每日 | chain_date |
| 实例探活 | 60 秒 | env_id |

### 12.3 恢复

- 启动时扫描过期租约任务，重新入队。
- 恢复不改变任务语义；已成功的发布不重发。
- Worker 崩溃不影响 Web 读与草稿编辑。

## 13 失败与降级

| 场景 | 行为 | 用户可见 |
|---|---|---|
| TongNCS 不可达 | 该环境只读；发布/回滚/恢复返回 `REL-503-001`，其他写接口返回 `SYS-423-001` | 目录标注“可能过期” |
| 实例恢复 | 仅恢复可写能力，不补发历史操作 | 需人工重新发起 |
| 审计写入失败 | 业务失败，不提交状态 | 500 + AUD-500-001 |
| 通知失败 | 重试 3 次；不回滚业务 | 站内仍可达 |
| 发布超时 | indeterminate；不自动重试 | 高优告警 + 对账入口 |
| Worker 崩溃 | 租约过期后接管 | 无数据丢失 |
| 数据库不可用 | 全站 503；不写 | 维护提示 |
| 批量部分失败 | 父单 PARTIAL_SUCCESS | 失败项可重试 |
| 权限服务异常 | fail-closed | 403 |

## 14 安全与合规

- 密码 Argon2id；登录锁定；会话空闲超时。
- 密钥：`NCG_CRYPTO_KEY`（32 字节 base64）与 `keyId`；支持双钥轮换。
- 传输：全站 HTTPS；TongNCS 与企微均 TLS。
- 日志：统一脱敏器；禁止打印请求体中的敏感字段。
- 审计：永久保留；归档介质与访问流程待 A-07。
- 导出：脱敏 + 校验值 + 导出审计。

## 15 可观测性

### 15.1 TraceId

入口生成或透传；写入 MDC；调用适配器时放请求头；审计与日志都带同一 TraceId。

### 15.2 指标

| 指标 | 类型 | 告警 |
|---|---|---|
| `ncg_publish_total{outcome}` | Counter | indeterminate > 0 |
| `ncg_publish_duration_seconds` | Timer | P99 超阈值 |
| `ncg_audit_write_failures_total` | Counter | > 0 |
| `ncg_direct_change_total{verdict}` | Counter | unauthorized > 0 |
| `ncg_notification_dead_total` | Counter | > 0 |
| `ncg_instance_up{env}` | Gauge | 连续 3 次失败 |
| `ncg_worker_lease_age_seconds` | Gauge | 无活跃租约 |
| `ncg_outbox_lag_seconds` | Gauge | 超 5 分钟 |

### 15.3 日志

JSON 字段：`ts, level, traceId, actor, action, objectType, objectId, result, errorCode, durationMs`；不含明文与凭证。

### 15.4 告警

- 发布 indeterminate、审计失败、实例不可用、未授权直连、通知 DEAD、Worker 无主。

## 16 测试策略

| 层级 | 范围 | 工具 |
|---|---|---|
| 单元 | 状态机、守卫、风险、哈希链 | JUnit 5 |
| 契约 | 适配器五方法、三态、异常映射 | 同一套测试跑假件与沙箱 |
| 集成 | 事务边界、唯一约束、并发、发件箱 | Testcontainers PostgreSQL |
| 接口 | 权限、错误码、幂等、分页 | MockMvc + WireMock |
| 端到端 | AC-01 ~ AC-26 | 真实或沙箱环境 |
| 故障注入 | 超时、500、审计失败、租约过期 | WireMock + 数据库故障 |

测试数据：固定应用、环境、映射与账号；敏感值使用假数据；不把生产配置带入测试。

## 17 部署与配置

### 17.1 启动顺序

1. PostgreSQL 可用。
2. Flyway 迁移完成。
3. Web 启动并注册健康检查。
4. Worker 启动并竞争 `worker:active`。
5. 探活通过后接入流量。

### 17.2 配置项

| 变量 | 说明 | 默认 |
|---|---|---|
| `NCG_DB_URL` | PostgreSQL 连接 | 无 |
| `NCG_DB_USER/PASSWORD` | 数据库凭证 | 无 |
| `NCG_CRYPTO_KEY` / `NCG_CRYPTO_KEY_ID` | 敏感值加密 | 无 |
| `NCG_TONGNCS_<ENV>_ENDPOINT/TOKEN` | 三环境实例 | 无 |
| `NCG_WECOM_WEBHOOK_DEFAULT` | 企微默认群 | 无 |
| `NCG_WORKER_ENABLED` | 是否启用 Worker | false |
| `NCG_CONTENT_MAX_BYTES` | 内容上限 | 1 MB [Assumption, TBD] |
| `NCG_BATCH_MAX_ITEMS` | 批量上限 | 50 [Assumption, TBD] |
| `NCG_GRAY_WINDOW_MINUTES` | 灰度观察 | 30 [Assumption, TBD] |
| `NCG_PUBLISH_WINDOW` | 发布窗口 | 全天 |
| `NCG_SCAN_INTERVAL_SECONDS` | 直连扫描 | 300 |

### 17.3 健康检查

- `/actuator/health/liveness`：进程存活。
- `/actuator/health/readiness`：数据库可用；Worker 额外检查租约。
- 不把 TongNCS 不可用作为 Web 下线条件，只影响对应环境写操作。

## 18 验收映射

### 18.1 FR 映射

| FR | 设计章节 | 关键接口/流程 |
|---|---|---|
| FR-01 目录与映射 | 2.3、5.2、6.2 | `/mappings`、`/configs` |
| FR-02 变更单与基线 | 4、5.3、7.1、7.5 | `/changes`、submit |
| FR-03 审批与职责分离 | 9.4、7.2 | approve/reject/transfer |
| FR-04 发布与读回 | 7.3、7.4、8 | `/release`、reconcile |
| FR-05 审计与直连 | 7.11、10 | `/audit-events`、scan |
| FR-06 身份与授权 | 9 | `/auth`、`/grants` |
| FR-07 影响面与风险 | 4.4、7.1 | `/impact` |
| FR-08 回滚与恢复 | 7.6、7.7 | rollback、restore |
| FR-09 通知 | 11 | outbox |
| FR-10 多环境连接 | 2.2、5.2、8.3 | `/instances` |
| FR-11 跨环境同步 | 7.8 | `/env-sync` |
| FR-12 定时与批量 | 7.9、7.10、12 | schedule、batch |

### 18.2 AC 映射

| AC | 设计落点 | 验证方式 |
|---|---|---|
| AC-01 | 5.4 映射唯一约束 | 集成测试冲突保存 |
| AC-02 | 7.1 草稿只落平台 | 读 TongNCS 对比 |
| AC-03 | 5.4 活跃单唯一索引 | 并发建单测试 |
| AC-04 | 7.5 基线漂移 | 审批后改 TongNCS |
| AC-05 | 9.4 职责分离 | 审批页/接口 403 |
| AC-06 | 9.4 高风险守卫 | 非负责人审批被拒 |
| AC-07 | 9.4 应用负责人例外 | 发布成功 + 审计 |
| AC-08 | 7.3 读回 | MD5 比对 |
| AC-09 | 7.4 indeterminate | 注入超时 |
| AC-10 | 6.5 幂等 | 重复发布返回既有记录 |
| AC-11 | 10.4 防篡改 | 拒绝 UPDATE/DELETE |
| AC-12 | 10.2 审计同事务 | 注入审计失败 |
| AC-13 | 7.11 直连检测 | 控制台直改 + 5 分钟 |
| AC-14 | 7.11 盲区 | 制造历史缺口 |
| AC-15 | 7.7 第 29 天恢复 | 时间旅行 |
| AC-16 | 7.7 同名冲突 | 冲突恢复 |
| AC-17 | 4.4 影响面 | 订阅者映射 |
| AC-18 | 9.5 敏感值 | 原因 + 审计 |
| AC-19 | 11.3 通知重试 | 企微失败 |
| AC-20 | 7.8 同步 | 只生成目标单 |
| AC-21 | 7.9 定时复核 | 到点改基线 |
| AC-22 | 7.10 批量部分成功 | 失败子项重试 |
| AC-23 | 13 只读降级 | 断开实例 |
| AC-24 | 5.4 实例唯一 | 重复绑定 |
| AC-25 | 1.2 无紧急发布 | 入口与接口均无 |
| AC-26 | 9.3 权限即时失效 | 回收权限后发布 |

## 19 未决项与自检

### 19.1 未决项

| 编号 | 事项 | 本设计的处理 |
|---|---|---|
| A-01 | 高风险阈值 | 规则已定义因子，阈值配置化，标 `[TBD]` |
| A-02 | 内容上限 | 默认 1 MB，`[Assumption, TBD]` |
| A-03 | 批量上限 | 默认 50，`[Assumption, TBD]` |
| A-04 | 灰度窗口 | 默认 30 分钟，`[Assumption, TBD]` |
| A-05 | 敏感识别 | 采用“标记 + 命名规则”双轨，具体规则 `[TBD]` |
| A-06 | 代理人 | 预留 `ncg_approval_delegate`，流程 `[TBD]` |
| A-07 | 审计归档 | 在线保留 + 永久归档，介质与访问 `[TBD]` |
| A-08 | 密码策略 | 设计给出建议默认值，标 `[Assumption, TBD]` |
| G-13 | 高风险阈值可执行 | 同 A-01 |
| G-14 | 批量上限可执行 | 同 A-03 |
| G-15 | 审计保留合规 | 同 A-07 |
| G-16 | 治理文档 | 已交付；静态报告改判留待下一版 |

### 19.2 自检

- [x] 章节覆盖架构、工程结构、领域、数据库、接口、流程、适配器、权限、审计、通知、Worker、失败、安全、可观测、测试、部署、验收映射。
- [x] 12 项 FR 与 26 个 AC 均有设计落点。
- [x] 6 组状态机与 spec E.2 一致。
- [x] 发布、建单、映射、实例、授权、直连、通知、批量均有唯一约束。
- [x] 外部调用在事务外，状态与审计同事务。
- [x] 未知值均标注 `[Assumption, TBD]` 或 `[TBD]`，未臆造业务阈值。
- [x] 全文不含可执行代码、迁移脚本或脚手架。
- [x] 39 条错误码在第 6.6 节全部映射到入口，无斜杠简写、无自造编码。
- [x] 敏感值查看端点 `POST /configs/{mappingId}/sensitive-view` 与审计动作 `CONFIG_SENSITIVE_VIEW` 一致。
- [x] 通知绑定表 `ncg_notify_binding` 与 `/notify-bindings` 端点一致，符合“应用 × 环境绑定群”。
- [x] 前端契约缺口已闭合：`format` 持久化列、`items[]` 返回、敏感值按 `itemKey`、批量逐项值与草稿编辑值端点。
- [x] 审计动作（37 个）与通知事件类型（11 个）已固定枚举并声明版本化。
- 验证结论（2026-11-22）：20 章齐全；FR 12 行、AC 26 行连续无缺口；6 组状态机与 spec E.2 一致；全文错误码 39 条与 spec E.3 集合完全一致，第 6.6 节全覆盖且无斜杠简写；接口清单 48 个端点；21 处未知值均带 `TBD` 标注；仓库无代码产物；`agent.md`、静态报告、`tasks.md`、`checklist.md` 已同步。

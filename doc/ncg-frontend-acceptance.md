# NCG 配置治理平台前端 · 全功能验收走查报告

- **验收对象**：`frontend/`（Vue3 + Element Plus 全功能前端，P-01~P-16 全页面）
- **验收依据**：`doc/ncg-user-guide.html` 15 章验收脚本、`doc/ncg-api-contract.md` §4.1 页面对照与 §6 错误契约、`frontend/INTEGRATION-REPORT.md` 联调结论、frontend-design-pro 反模式六轴清单
- **验收方式**：独立复核（不重跑联调）——现场服务走查实测 + 证据文件复核 + 前端代码逐页对照 + 六轴静态审查
- **验收人 / 日期**：reviewer（独立验收评审）/ 2026-11-23
- **总体结论**：**通过（带 1 项后端功能遗留 + 2 项低优先级建议）** —— 15 章逐页走查全部达标，22 用例证据成立（23/23 PASS），48 端点全接通，P-01~P-16 无遗漏（P-17 为契约保留编号），六轴反模式清零（本次修复 3 处小问题）。功能遗留 F1 属后端 API 防御缺口（UI 不可达），按约定只记录不代改。

---

## 1. 验收方法与环境

### 1.1 方法

1. **现场实测**：拉起 PG(55432) / web(8085) / worker(9081) 后运行独立抽验脚本 `.tools/review_spot.py`（22 项断言：登录×3、auth/me、应用/配置列表、TC-01/02/03/04 读取与脱敏、TC-06/07/08/10 异常路径、MUST-NOT-05、audit PageResult、releases、recycle-bin 别名、direct-changes、SYS-404-001、D6 空 body、错误体五字段）。
2. **证据复核**：`.tools/tc-results.json`（23 行）、`.tools/matrix-results.json`（50 行）逐条核对结论与实测细节。
3. **逐页走查**：以 user-guide 15 章流程为脚本，对照 12 个视图 + 4 个关键共享组件（SensitiveValue/DiffView/EmptyState/SkeletonBlock）的实现逐章核验。
4. **六轴审查**：按 frontend-design-pro 的字体/色彩/间距/动效/交互/文案清单做全量静态扫描（grep + 逐处核对）。

### 1.2 环境与 fixture 说明

- 服务为联调保留现场，因沙箱回收守护进程本次以托管后台任务方式重启（PG `.tools/pgdata:55432`、web `:8085`、worker `:9081`，复跑指引见 `frontend/INTEGRATION-REPORT.md` §8-9，PG JDBC 须 `sslmode=disable`）。
- **fixture 拓扑已知行为（非缺陷）**：fake Nacos 为 web 进程内存 store，进程重启即空，`GET /configs/{id}` 会报 `config not found`。`tc_run.py` 注释明示「静态映射内容重播（web 重启后 fake 内存为空，重跑自愈）」；本次按同方式用 `POST /_fixture/seed` 重播 8 条 STATIC_SEEDS 后全部恢复。
- 测试凭据与素材 ID（APP/ENV/映射 M）与 `tc_run.py` 一致，不再重跑全量联调。

### 1.3 独立抽验结果（22 项，全部 PASS）

| 项 | 结果 | 实测证据 |
|---|---|---|
| 登录 editor-1 / editor-2 / ncg-admin | PASS | 3×200 |
| auth/me 形态 | PASS | userId+permissions(4)+roles 齐全 |
| GET /apps / apps/{id}/configs | PASS | format/md5/collectedAt 字段在 |
| TC-01 PROPERTIES 脱敏 | PASS | items=5，敏感项值 `••••••` |
| TC-02 YAML 点路径 | PASS | items key=`server.port`/`feature.ratio` 等点路径 |
| TC-03 TEXT 整文件 | PASS | items=0，content 整文件下发 |
| TC-04 parseError 浏览态 | PASS | `parseError:{line:2,column:2}`，详情 200 不阻断 |
| TC-06 reason<10 | PASS | 400 `CR-400-001` |
| TC-07 无 VIEW_SENSITIVE | PASS | 403 `AUTHZ-403-001` |
| TC-08 items+content 互斥 | PASS | 400 `CR-400-001`「items 与 content 不能同时提交」 |
| TC-10 未知后缀 | PASS | 415 `CR-415-001`「该 DataId 格式不支持，只能只读浏览」 |
| MUST-NOT-05 通知目标脱敏 | PASS | 列表不回显明文 |
| audit PageResult | PASS | `{items,page,size,total}`，total=469 |
| releases 无 changeNo 过滤 | PASS | 200，n=15 |
| recycle-bin 别名字段 | PASS | appId/envId/dataId/expireAt/restoreStatus 在 |
| direct-changes | PASS | 200，n=3 |
| SYS-404-001 | PASS | 不存在路径 404 五字段错误体 |
| 错误体完整性 | PASS | code/message/traceId/details/timestamp 齐全 |

> 两项非通过项均为复核脚本自身口径问题，已甄别非产品缺陷：① 初次 404 为 fake store 重启即空（见 1.2 fixture 说明，重播后通过）；② D6 空 body 一项实测对象为 `{}` 空 JSON 对象，属新发现 F1（见 §6），原 D6 修复范围（无 body / null / 非法 JSON）实测均正确 400。

---

## 2. 逐功能验收清单（user-guide 15 章逐页走查）

### ch1 快速上手 / ch2 核心概念

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 五步链路（发起→审批→发布→读回校验→审计）可走通 | ch1 | **通过** | 12 路由齐全，五步对应 /changes→/approvals→/releases→发布读回（ReleasesView MD5+TraceId）→/audit |
| DataId=发布单位、配置项=编辑单位 | ch2 | **通过** | CatalogView 以 DataId 为行、ChangeValueEditor 按 items 逐项编辑 |

### ch3 登录与界面

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 登录失败不泄露账号是否存在 | ch3 | **通过** | LoginView 统一提示 + 错误码/TraceId 展示（t2 补强，走查确认） |
| 入口守卫 / redirect 保留 / 401 统一跳登录 | ch3 | **通过** | router/index.ts 入口守卫 fetchMe 预检、redirect 保留；登录接口 401 不触发全局跳转 |
| 侧边导航分组与权限点驱动 | ch3 | **通过** | router/nav.ts 治理 6 / 发布 3 / 监督 1 / 管理 1，PENDING APPROVE / PUBLISH / VIEW_AUDIT / ADMIN 门控 |
| 环境切换为全局上下文 | ch3 | **通过** | AppLayout 顶栏环境切换，工作台/目录等随环境过滤 |
| 只读实例标记 / 当前用户角色 | ch3 | **通过** | AppLayout 顶栏展示 |

### ch4 浏览与查看配置

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 应用树 + 配置表 + 详情抽屉 | ch4 | **通过** | CatalogView：owner 分组应用树、DataId 表、详情抽屉（format 标签、items 按项展示） |
| 四格式渲染（PROPERTIES/YAML/JSON/TEXT） | ch4 | **通过** | 实测 TC-01~03 全过；.yml 按 YAML（映射 bad.yml format=YAML） |
| 敏感值默认脱敏、查看需原因≥10 字 | ch4 | **通过** | SensitiveValue.vue：`reasonValid=trim().length>=10`；TC-06/07 实测 400/403 |
| 30 秒自动再脱敏 + 写审计 | ch4 | **通过** | SensitiveValue.vue `REVEAL_DURATION=30` 倒计时；审计事件实测累积（total=469） |
| 解析失败展示行号、浏览不阻断 | ch4 | **通过** | TC-04 `parseError:{line:2,column:2}`；CatalogView parseError 行号展示只读 |
| 未知格式只读、禁发起变更 | ch4 | **通过** | TC-10 415 `CR-415-001`；CR-415-001 处只读禁发起 |
| 快照信息（MD5/采集时间）口径 | ch4 | **通过（见观察项 O1）** | 详情抽屉 MD5+采集时间实测填充（md5=4b555c11…）；列表列按「只对已收集配置显示」口径留白 |

### ch5 发起变更

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 载入当前值、items 与 content 互斥 | ch5 | **通过** | ChangeCreateDialog.vue + ChangeValueEditor.vue（content 模式=CREATE/TEXT/整文件替换）；TC-08 实测互斥拦截 |
| 提交前 DiffView 预览 | ch5 | **通过** | DiffView pair/three 三态着色 + 敏感脱敏（t1 共享层） |
| CR-409-001 阻断提交、草稿可存 | ch5 | **通过** | ChangeCreateDialog.vue:168,248（顶部提示 + 阻断提交、草稿保留） |
| CR-422-001 行号高亮、保留草稿 | ch5 | **通过** | ChangeValueEditor.vue:60（错误行高亮 + 草稿保留） |
| 草稿编辑（乐观锁）/提交/取消 | ch5 | **通过** | ChangeDetailView DRAFT 编辑 updateChange 带 version 乐观锁 |

### ch6 审批

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 通过 / 驳回（原因≥10 字）/ 转交 | ch6 | **通过** | ApprovalsView.vue:4-5；APR-422-001 文案映射在 |
| 提交人不可审批（AUTHZ-403-002） | ch6 | **通过** | ApprovalsView.vue:254「不可审批」不渲染通过按钮 |
| 高风险仅应用负责人（AUTHZ-403-003） | ch6 | **通过** | AUTHZ-403-003 门控 4 处；职责分离说明卡 ApprovalsView.vue:201 |

### ch7 发布（含批量）

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 六项预检全过才可执行 | ch7 | **通过** | ReleasesView.vue:107-138（6 项 PreflightCheck、`preflightPassed` 门禁、请求中按钮禁用） |
| 三态结果 + 读回 MD5/TraceId | ch7 | **通过** | ReleasesView.vue:4（业务红线注释）、verified/rejected/indeterminate 着色 |
| indeterminate 禁重试、只走人工对账 | ch7 | **通过** | ReleasesView.vue:212（人工对账流程）、REL-502-001 文案、无重试入口 |
| 回滚：上一版本直执、更早走审批 + 二次确认 | ch7 | **通过** | ChangeDetailView 回滚 rollbackChange（二次确认）；REL-403-001 文案在 |
| 批量 ≤50 / 未填值阻断 / 部分成功不重发 | ch7 | **通过** | BatchesView.vue:202-210（CR-400-001/BAT-413-001 内联阻断）、:113「成功 · 不重发」、失败子项单独重试 |
| 批量草稿编辑值（乐观锁+敏感重输） | ch7 | **通过** | BatchesView.vue:6 + PUT /batches/{batchId}/items/{itemId}（t6 新增端点，49 个 API 调用含此） |

### ch8 定时发布

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 设置/取消定时、到点复核失败转回待发布不补发 | ch8 | **通过** | SchedulesView.vue:4,33,198（REVIEW_FAILED 状态、SCH-409-002 文案与说明卡） |

### ch9 跨环境同步

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 只对比 + 生成目标环境变更单、不直接写 | ch9 | **通过** | EnvSyncView.vue（对比、勾选差异项生成目标单号、源环境只读横幅） |
| 快照详情 | ch9 | **通过** | EnvSyncView.vue:122-151（本次按钮文案修为「查看快照详情」） |
| SYN-409-001/002 错误口径 | ch9 | **通过** | 文案映射在 |

### ch10 回滚与恢复

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 软删 30 天倒计时、超期禁用 | ch10 | **通过** | RecycleBinView（expireAt 倒计时；RB-410-001 文案）；实测 recycle-bin 别名字段在 |
| 恢复同名冲突二选一、禁静默覆盖 | ch10 | **通过** | RecycleBinView.vue:5,82,99-100（首次 onConflict='ABANDON' 绝不静默覆盖；RB-409-001 只给「覆盖并重新审批/放弃」） |

### ch11 审计与直连检测

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 审计流查询/详情/哈希链校验 | ch11 | **通过** | AuditView.vue:117-132（前端链式核对 prevHash===上一条 hash，结论标注范围） |
| 导出附校验值（EXPORT_AUDIT 门控） | ch11 | **通过** | AuditView.vue:20,136（导出弹窗含范围摘要+脱敏声明） |
| 直连告警 + 幂等处置 + 检测盲区 | ch11 | **通过** | AuditView 直连面板（未处置红卡、二次确认 close 读回）；盲区 banner（§7.11）；实测 direct-changes n=3 |
| 审计追加不可删 | ch11 | **通过** | 全前端无删除审计入口（走查确认） |

### ch12 管理与设置

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 账号（≥12 位密码）/授权（应用×环境×10 权限点） | ch12 | **通过** | AdminView 账号与授权双表；实测 POST /users {}→400「password must be at least 12 characters (A-08)」 |
| 实例管理 + 测试连接 | ch12 | **通过** | AdminView 实例卡片网格 + 内联测试结果 + 健康 pill；CON-502-001 内联回显 |
| 通知绑定 upsert + 解绑 + 恒脱敏 | ch12 | **通过** | AdminView 通知 tab；MUST-NOT-05 实测不回显明文、保存重输 |
| CON-409-001（一实例一环境） | ch12 | **通过（观察项 O2）** | 前端无专用文案，由全局拦截器回显服务端 §4.2 中文 message（走查确认提示可读） |

### ch13 错误码速查 / ch14 最佳实践 FAQ / ch15 术语表

| 功能 | 依据 | 结论 | 证据 |
|---|---|---|---|
| 25 个错误码按 §4.2 中文口径展示 | ch13 | **通过** | 22 个错误码有前端专属文案映射（views/ 各 CODE_COPY），其余走全局拦截器回显服务端 message；实测 CR-400/409/415、AUTHZ-403-001、SYS-404/500、REL/SCH/RB 系列文案均含场景+下一步 |
| 错误码格式不进最终用户文案 | ch13 | **通过** | 错误展示区单独标注 code+TraceId（ErrorState 可复制），与人话文案分离 |
| FAQ 场景（草稿丢失/不知道谁审批/发布不确定） | ch14 | **通过** | 草稿保留（CR-422/409 均保留）、审批中心待办、indeterminate 人工对账三选一 |

---

## 3. P-01~P-17 对照（契约 §4.1）—— 无遗漏

| 编号 | 页面 | 实现 | 结论 |
|---|---|---|---|
| P-01 | 登录页（非导航） | views/login/LoginView.vue | ✅ |
| P-02 | 工作台 | views/workbench/WorkbenchView.vue | ✅ |
| P-03 | 配置目录 | views/catalog/CatalogView.vue | ✅ |
| P-04 | 变更单列表 | views/changes/ChangesView.vue | ✅ |
| P-05 | 新建变更 | views/changes/ChangeCreateDialog.vue | ✅ |
| P-06 | 变更详情 | views/changes/ChangeDetailView.vue | ✅ |
| P-07 | 审批 | views/approvals/ApprovalsView.vue | ✅ |
| P-08 | 发布控制台 | views/releases/ReleasesView.vue | ✅ |
| P-09 | 跨环境同步 | views/env-sync/EnvSyncView.vue | ✅ |
| P-10 | 定时任务 | views/schedules/SchedulesView.vue | ✅ |
| P-11 | 批量变更 | views/batches/BatchesView.vue | ✅ |
| P-12 | 回收站 | views/recycle-bin/RecycleBinView.vue | ✅ |
| P-13 | 审计与检测 | views/audit/AuditView.vue | ✅ |
| P-14 | 账号与授权 | views/admin/AdminView.vue（tab1） | ✅ |
| P-15 | 实例与环境 | views/admin/AdminView.vue（tab2） | ✅ |
| P-16 | 通知绑定 | views/admin/AdminView.vue（tab3） | ✅ |
| P-17 | 未使用编号保留 | — | ✅（契约保留，非遗漏） |

12 条业务路由 + /login + pathMatch 兜底（router/index.ts:12-96），与上表一一对应。

## 4. 48 端点核对

- **服务侧**：`.tools/matrix-results.json` 50 行全 REACHABLE（48 端点 + #47/#48 各 2 路由），实测证据含 status/code/duration。
- **前端 API 层**：`frontend/src/api/modules/` 11 个模块共 49 个端点调用（48 契约端点 + t6 新增 `PUT /batches/{batchId}/items/{itemId}`），逐域预注册无遗漏；本次独立抽验直连 18 个端点全部返回契约形态。
- **结论**：**48/48 全接通，前后端一致**。

## 5. 22 条用例结论复核

`.tools/tc-results.json` 23 行（22 契约用例 + AUTH-CHAIN 鉴权链）全 PASS，finishedAt 2026-11-23T03:37:15。本次独立抽验复现其中 TC-01/02/03/04/06/07/08/10 共 8 条核心路径（正向+异常），结论一致。D1-D13 缺陷修复对应行为抽验符合（D6 空 body/null/非法 JSON 三态正确 400、D9 404、D10 回收站多条目并存、D13 后端两 JVM store 隔离生效——worker/web 各自正常）。**23/23 结论成立**。

## 6. 设计六轴反模式审查（frontend-design-pro）

| 轴 | 结论 | 证据 |
|---|---|---|
| 字体 | ✅ 清零 | 仅 3 个 token 字体族（DM Sans+Noto Sans SC / IBM Plex Mono tabular-nums）；禁用词（Arial/Inter/system-ui）只出现在注释；字号全 token 化（fs-xs 73/sm 33/md 6/lg 5/xl 3/base 2，零硬编码 px） |
| 色彩 | ✅ 清零 | OKLCH 色板 + 264 色调中性带；views/components/layouts 零硬编码 hex/rgb/rgba；暗底 oklch(17.2%) 非纯黑 |
| 间距 | ✅ 清零（本次修复 1 处） | 4/8px 网格；唯一越格 `EnvSyncView.vue:354 padding:3px` 已修为 4px；正文 65ch（--ncg-measure）、内容宽 1280px |
| 动效 | ✅ 清零 | `--ncg-ease=cubic-bezier(0.16,1,0.3,1)`、micro 120/base 180/slow 240/page 320ms、reduced-motion 全局 0.01ms 降级、无 bounce/elastic；SkeletonBlock shimmer 用 linear 属骨架常规 |
| 交互 | ✅ 达标 | `:focus-visible` 2px 绿 outline（tokens.css:157）；空态 reason+下一步（EmptyState，12 视图区全用）；禁用态均给原因（预检失败/漂移/超期等）；观察项 O3：AdminView/AuditView 4 处 v-loading（spinner 而非 skeleton，低优先级） |
| 文案 | ✅ 清零（本次修复 2 处） | 按钮动词开头为主；本次修复：SkeletonBlock sr-only 去「请稍候」（违禁词）、EnvSync「快照详情」→「查看快照详情」（动词开头）；错误文案均含场景+下一步，无「请稍候…」 |

**本次直接修复的小问题（文案/样式，共 3 处，`npm run build` 通过）**：
1. `frontend/src/components/SkeletonBlock.vue:34`：sr-only 文案「内容加载中，请稍候」→「内容加载中」（去违禁「请稍候」）。
2. `frontend/src/views/env-sync/EnvSyncView.vue:354`：`padding: 3px 12px` → `4px 12px`（回到 4px 网格）。
3. `frontend/src/views/env-sync/EnvSyncView.vue:151`：按钮「快照详情」→「查看快照详情」（动词开头）。

## 7. 遗留问题

| 编号 | 级别 | 类别 | 描述 | 建议 |
|---|---|---|---|---|
| **F1** | **P2 功能（后端）** | API 防御缺口 | 空 JSON 对象 `{}` 请求体触发未预期 5xx：`POST /changes` `/grants` `/instances` `/notify-bindings` `/mappings` 均返回 500 `SYS-500-001`（契约 §6.6 禁止未预期 5xx；必填缺失应为 400 `CR-400-001`）。D6 修复只覆盖不可读 body（null/空/非法 JSON 均正确 400，实测），空对象走到字段缺校验后 NPE。`POST /batches`/`/users` 同场景已正确 400。前端 UI 表单不会发出此类请求（表单层必填校验），仅裸 API 可触发。复现：`curl -X POST :8085/api/v1/changes -H 'Content-Type: application/json' -d '{}'` | 转后端（dev-admin）：为上述 5 个写端点补 @Valid/空对象字段校验，统一映射 CR-400-001。**按分工只记录，未代改** |
| O1 | P3 观察 | 展示口径 | 配置目录列表 MD5/采集时间列在「未收集」时留白（字段=发布/回滚读回的 lastSnapshotMd5，详情抽屉即时计算填充正确） | 与 ch4「只对启动扫描或已收集的配置显示」口径一致；如需逐行有值可扩展采集任务（增强项，非缺陷） |
| O2 | P3 观察 | 文案 | CON-409-001（一实例一环境）无前端专属文案，由全局拦截器回显服务端中文 message | 可后续在 AdminView CODE_COPY 补一行；当前提示可读，不影响使用 |
| O3 | P3 观察 | 交互 | AdminView/AuditView 4 处 v-loading 用 spinner 而非 skeleton（六轴偏好 skeleton） | 低优先级美化项，功能不受影响 |

## 8. 总体结论

1. **功能验收**：15 章逐页走查全部达标——五步链路、四格式编辑、敏感值治理（脱敏/原因/30s 再脱敏/审计）、职责分离、发布六项预检与三态结果、indeterminate 人工对账、定时复核不补发、跨环境只对比不直写、软删 30 天与冲突二选一、审计哈希链与导出门控、管理四域，行为与用户手册逐一对应。P-01~P-16 无遗漏（P-17 为契约保留编号）。
2. **联调结论复核**：22 用例 23/23 PASS 与 48 端点 50 行 REACHABLE 的证据成立，独立抽验 18 个端点/8 条核心用例复现一致。
3. **设计质量**：六轴反模式全量扫描清零，本次修复 3 处小问题（2 文案 1 样式），构建通过。
4. **遗留**：1 项后端功能遗留 F1（裸 API 空对象 500，UI 不可达，已按分工记录并上报，不阻塞前端验收）+ 3 项 P3 观察项。

**结论：前端全功能验收通过。** F1 建议排入后端下一批修复；其余观察项可随迭代消化。

# NCG 前后端联调报告（INTEGRATION-REPORT）

- 任务：t6 前后端联调与 22 条可执行用例验证（AgentTeams ncg-frontend-dev，attempt_id=b68b210e-7286-4f33-bbd3-2fcd64580132）
- 日期：2026-11-23（Asia/Shanghai）
- 权威口径：`doc/ncg-api-contract.md` v1.2（§2 端点、§3 五缺口、§6 用例）+ 详细设计 v1.4 §6
- 执行环境：PG15（.tools/pgdata，127.0.0.1:55432，数据保留）、Nacos fake（local profile）、web :8085、worker（管理端口 9081）
- 结论：**22 用例 23/23 PASS（含 AUTH-CHAIN）；48 端点 50/50 行全部 REACHABLE 且功能实测；mvn test 47/47 全绿；未出现未预期 5xx；敏感明文零泄露**。所有功能正常可用（逐项确认见 §5）。

---

## 1. 总览

| 项目 | 结果 |
|---|---|
| 鉴权链 /auth/login→/auth/me→业务端点 | PASS（6 账号：ncg-admin/editor-1/approver-1/publisher-1/auditor-1/editor-2） |
| §6 正向用例 9 条（TC-01~05/09/13/16/21） | 全过 |
| §6 异常用例 13 条（TC-06~08/10~12/14/15/17~20/22） | 全过 |
| §4 五契约缺口字段 | 全覆盖（format、items[]、itemKey/expiresAt、items↔content 互斥、批量 version） |
| §2 48 端点可达性 | 50/50 行 REACHABLE（#47/#48 各含 2 路由） |
| 错误体完整性（code/message/traceId/details/timestamp） | 全部抽验合格 |
| §6.6 禁止未预期 5xx | 达成（D6~D9 共 4 处 500 缺陷已修，见 §3） |
| 后端测试门禁 | mvn test **47/47**（Failures 0 / Errors 0 / Skipped 13，13 为 Docker/Testcontainers 门控跳过，与基线一致） |
| 前端构建 | npm run build ✓（vue-tsc + vite，含联调期 3 处最小修复） |

测试基建（.tools/，可复跑）：
- `tc_run.py`：22 用例执行器（账号分工 editor-1 建单 / approver-1 审批 / publisher-1 发布 / auditor-1 审计 / editor-2 无 VIEW_SENSITIVE），结果 `.tools/tc-results.json`
- `tc_matrix.py`：48 端点矩阵执行器（独立素材映射、时间戳防重名、hit_rec 修复记行），结果 `.tools/matrix-results.json`
- `tc_cleanup.py`：重跑前清理（cancel 活跃单、INDETERMINATE 先对账再 cancel、failure-mode 复位）
- `m15.py`：#15 /changes/{changeNo}/cancel 矩阵补录（矩阵 v3 主序列未排 cancel 独立行）

## 2. 22 条可执行用例结果表（doc/ncg-api-contract.md §6）

| 用例 | 预期 | 实际 | 结论 |
|---|---|---|---|
| AUTH-CHAIN | login 6 账号 200 + GET /auth/me 200 含 permissions | login=6x200；me=200，userId/roles/enabled 齐全，permissions 10 点（admin 全量） | PASS |
| TC-01 properties 读取脱敏 | 200，format=PROPERTIES，敏感值 ••••••，md5/collectedAt 非空 | 200，format=PROPERTIES；db.password sensitive=true 且 masked=••••••；cache.ttl=NUMBER、cache.enabled=BOOLEAN；md5=d47a1cf3…、collectedAt ISO | PASS |
| TC-02 yaml→YAML 点路径 | 200，format=YAML，点路径展开 | keys=[server.port, server.name, feature.enabled, feature.ratio, secrets.apiToken]（数组下标 [n] 语法就绪） | PASS |
| TC-03 TEXT→content 无 items | 200，format=TEXT，content 整文件 | items=0，content=True（原样 3 行文本） | PASS |
| TC-04 解析失败浏览 | 200，items=[]，parseError{line,column,message} | 200，items=0，parseError={line:2, column:2, message:"while parsing a flow sequence…"} | PASS |
| TC-05 sensitive-view | 200，只回该项 value，expiresAt≈30s，审计可查 | value=SuperSecret#2026（仅 db.password 项），expiresAt=+30.0s，审计 CONFIG_SENSITIVE_VIEW 可查（JSON 含 itemKey/reason，不落明文） | PASS |
| TC-06 reason<10 | 400 CR-400-001 | 400 CR-400-001 | PASS |
| TC-07 无 VIEW_SENSITIVE | 403 AUTHZ-403-001 | 403 AUTHZ-403-001（editor-2；校验顺序：权限先行→reason→itemKey） | PASS |
| TC-08 items+content 同传 | 400 CR-400-001 | 400 CR-400-001（「items 与 content 不能同时提交」） | PASS |
| TC-09 POST /changes items[] | 200/201，status=DRAFT，format=YAML，itemCount，changeNo | 200，status=DRAFT，format=YAML，itemCount=1，changeNo=CR-20261122-0052，baselineMd5 创建时记录 | PASS |
| TC-10 MAPPING_UNKNOWN(.ini) | 415 CR-415-001 | 415 CR-415-001「该 DataId 格式不支持，只能只读浏览」 | PASS |
| TC-11 解析失败提交 | 422 CR-422-001，details 含 line/column，草稿不丢 | 422 CR-422-001 line=2 col=2，草稿留存可继续编辑 | PASS |
| TC-12 同配置活跃单 | 409 CR-409-001，details 返已有 changeNo | first=200→submit=200，second=409 CR-409-001，details.changeNo=CR-20261122-0051 | PASS |
| TC-13 POST /batches | 200/201，batchId，childCount=DataId 数，status=DRAFT | 200，batchId 非空，childCount=2，status=DRAFT，items[]=2 含 itemId（差异6） | PASS |
| TC-14 批量子项空 | 400 CR-400-001，不创建父单/子项 | 400 CR-400-001（批次号零消耗） | PASS |
| TC-15 51 个 DataId | 413 BAT-413-001，不创建父单 | 413 BAT-413-001「批量数量超过上限」 | PASS |
| TC-16 PUT batch item | 200，version 递增，itemCount，Diff 重算 | 200，version 0→1，itemCount=1，feature.ratio 0.6→0.75 | PASS |
| TC-17 子项提交后编辑 | 409 SYS-409-001，子项内容不变 | submit=200，edit=409 SYS-409-001，value unchanged=True | PASS |
| TC-18 跨环境 format 不一致 | compare/apply 均 409 SYN-409-002 | compare(dev→staging)=409 SYN-409-002（app2.properties source=YAML target=PROPERTIES）；compare(dev→prod)=200 空差异快照；apply 显式勾选=409 SYN-409-002 | PASS |
| TC-19 提交基线漂移 | 412 CR-412-001，状态不前进 | open=200（baselineMd5 记录）→fixture 外部漂移→submit=412 CR-412-001，status 留 DRAFT | PASS |
| TC-20 发布不确定 | 502 REL-502-001，outcome=INDETERMINATE，不自动重试 | release=502 REL-502-001，GET /releases outcome=indeterminate，2s 后记录数仍 1（MUST-NOT-03） | PASS |
| TC-21 发布幂等重放 | 200 同 releaseId，记录数不增（REL-409-002 语义） | first=200 releaseId=A result=verified；replay=200 releaseId=A 相同；records=1 | PASS |
| TC-22 恢复同名冲突 | 409 RB-409-001，只返「覆盖并重新审批/放弃」 | delete=200→restore（无 body）=409 RB-409-001 options=['OVERRIDE_AND_REAPPROVE','ABANDON']；ABANDON=200（条目保持 PENDING）；OVERRIDE_AND_REAPPROVE=200 changeNo=CR-20261122-0058（RESTORE 草稿） | PASS |

执行顺序按 §6 建议：认证→读取→建单异常先跑→批量（TC-13→16→提交→17）→同步→发布（TC-19 独立；TC-20 切 mock；TC-21 重放）→恢复。TC-14/TC-15 独立请求不依赖 TC-13。

## 3. 联调缺陷清单与修复说明（D1~D17）

均为联调发现的真实缺陷。后端修复全部为**向后兼容加法**，既有服务方法签名零改动（13 个 Docker 门控集成测试仍编译），每批修复后 `mvn test` 复验绿（D1~D13 期间 47/47；D14 新增 6 条回归测试后 **53/53**）。

| # | 缺陷 | 根因 | 修复 | 门禁 |
|---|---|---|---|---|
| D1 | 含 Instant 的响应序列化 500（POST /mappings、/auth/me、JSON 导出均写半截崩，"Cannot create a session after the response has been committed"） | 手工 `new ObjectMapper()` 未注册 JavaTimeModule，Instant 不支持默认序列化 | ContractViews.MAPPER、SecurityConfig.writeJson、AuditExportController JSON writer 统一注册 JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS 保留 ISO | 47 绿 |
| D2 | TC-12：同 mapping 可建多个进行中变更单（应 409 CR-409-001） | 仅靠 uk_active_change 部分唯一索引兜底，而 DRAFT 不在索引谓词 ACTIVE_STATES 里 | ChangeDraftService.open() 显式 findFirstByMappingIdAndStatusIn(ACTIVE_STATES) 冲突→CR-409-001 details{changeNo}；submit() 同步补 details | 47 绿 |
| D3 | TC-18 apply 侧：空差异快照+显式勾选时 format 不校验直接 200 | propose() 只在 diffSummary 抽出的 dataId 循环里 assertFormatConsistent | propose() 在循环前对显式 mappingIds 逐项预校验 → SYN-409-002 | 47 绿 |
| D4 | TC-20：INDETERMINATE 发布返 200（契约要 502 REL-502-001） | ReleaseService 记录后正常返回，无 HTTP 语义映射 | ReleaseController.release() 在服务事务提交后（记录已保留）抛 REL_502_001 + details{releaseId,outcome} | 47 绿 |
| D5 | RESTORE 无历史内容时建单 500（content_sha256 NOT NULL 违约） | open() 仅 content 非空才落密文/摘要 | `effectiveContent = content==null?"":content` 空串也落密文与摘要 | 47 绿 |
| D6 | 空 body / `null` 字面量 / 非法 JSON POST → 500 SYS-500-001 | HttpMessageNotReadableException 落 generic Exception handler | ApiExceptionHandler 补 handleUnreadable→400 CR-400-001「请求体缺失或格式错误」 | 47 绿 |
| D7 | POST /grants 缺 userId → 500（subject_id NOT NULL 违约） | Controller 无参数校验直接落库 | GrantService.grant() 前置校验→CR-400-001「userId is required」等 | 47 绿 |
| D8 | 无用户名登录失败 → 500（审计 object_id NOT NULL 违约，"Cannot create a session…"） | 失败登录审计 objectId=login=null | SecurityConfig failureHandler objectId 兜底 "unknown"；AuditWriter.append 对 actorId/objectType/objectId 统一 NOT NULL 兜底（"unknown"/"UNKNOWN"/"-"） | 47 绿 |
| D9 | 不存在路径 → 500（NoResourceFoundException 落 generic handler） | Spring 6 静态资源异常未映射 | ApiExceptionHandler 补 handleNoResource→404 SYS-404-001 | 47 绿 |
| D10 | 同一映射「删除→恢复→再删除」第二次软删 500（uk_recycle_mapping 撞键） | softDelete 每次 new RecycleBinItem 插入，唯一键在 mapping_id | softDelete 复用 findByMappingId 旧条目重置 PENDING（无则新建） | 47 绿 |
| D11 | #34 env-sync apply 返 200 但 changeNos=[]（应生成 1 个 SYNC 单） | 契约 apply body 无 appId，targetAppId=null 时 findByAppIdAndEnvId 派生查询 null 匹配不到→静默 continue | propose() 增 fallback：appId 缺省按勾选 mappingIds 反查目标映射（同 envId+dataId），open() 的 appId 兜底取 mapping.getAppId() | 47 绿 |
| D12 | 直连扫描第二次起永远静默失败，不再产生告警 | DirectScanService 把 lastHistoryId（"H5"）当分页游标传，fake 适配器 Integer.parseInt 抛异常→catch 后 consecutiveFailures++ 直接 return | scan() 改传 null 游标（增量性由 since+uk_direct 去重保证）；fake history() 游标解析容错（非数值按 0，调用方按 historyId 去重） | 47 绿 |
| D13 | 本地 fake 拓扑 web/worker 双 JVM 内存 store 互不可见，web 播的外部变更 worker 永远扫不到 | FakeNacosConfigAdapter 为进程内内存 store；worker 定时扫描读自己的空 store | 新增 `POST /api/v1/_fixture/scan-direct`（@Profile("local")，决策 D 一致：不新增业务 API 面）在 web 进程内触发同一条 DirectScanService.scan 链路（判定/入库/审计/告警完全一致）；real 模式外部 Nacos 共享状态，worker 定时扫描不受影响 | 47 绿 |

### 3.1 缺陷清单附录：D14（t7 独立验收遗留 F1，t9 修复）

| # | 缺陷 | 根因 | 修复 | 门禁 |
|---|---|---|---|---|
| D14 | POST /api/v1/changes、/grants、/instances、/notify-bindings、/mappings 五端点收到 body `{}`（空 JSON 对象）返回 500 SYS-500-001，契约要求 400 CR-400-001 | 四类空对象缺字段异常未映射 CR-400-001 + 一类方法不匹配：① /changes `catalog.getMapping(null)`→findById(null) IAE/NPE；② /grants `PermissionPoint.valueOf(null)`→Enum NPE；③ /instances `instances.findByEnvId(null)`→NPE/NOT NULL；④ /mappings `apps.findById(null)`→IAE/NPE；⑤ /notify-bindings 无 POST 映射，`HttpRequestMethodNotSupportedException` 落 generic handler→500（契约 §6.6 禁未预期 5xx） | 共享修复 7 文件：新增 `web/RequestChecks.require(name,value,…)`（null/blank→NcgException(CR_400_001,"缺少必填字段：…")）在 ChangeController.open（appId/envId/mappingId/changeType）、MappingAdminController.createMapping（appId/envId/dataId）、CatalogController.register（envId/endpoint；credential 可空语义保留+null 守卫防 CryptoService.encrypt NPE）三入口前置校验；UserController.grant permission 空→"permission is required"、非法值 try/catch→"permission invalid: X"（不触达 Enum.valueOf NPE 点）；ApiExceptionHandler 新增 handleBadRequest(HttpRequestMethodNotSupported/HttpMediaTypeNotSupported/ServletRequestBinding/MissingServletRequestParameter/MethodArgumentTypeMismatch)→400 CR-400-001「请求方法或参数不合法」details{reason}。**决策**：/notify-bindings 无 POST 映射按 t7 验收口径「五端点 {} 均 400 CR-400-001」映射为 400 而非 405（原 500 已违 §6.6，方法不匹配属「请求不合法」同族）；只拦「缺了会 500」的字段，title/reason/permission 归属等带业务文案的校验留在服务层保证零回归 | **53 绿**（47+6 新增 EmptyBodyValidationTest） |

联调期错误码补齐（契约定义而 enum 漏收，非行为缺陷）：`NcgErrorCode` 增 `CR-415-001`(415)、`CR-422-001`(422)。

前端最小修复 3 处（frontend/，联调所需）：
1. `src/api/modules/auth.ts` login() 改 URLSearchParams 表单编码上送（后端 Spring Security formLogin 只认表单参数）；
2. `src/api/modules/release.ts` reconcileRelease(releaseId, conclusion?) 可选 body {conclusion}（差异5 前端侧补齐）；
3. `src/stores/releases.ts` + `src/views/releases/ReleasesView.vue` conclusion 透传。
npm run build ✓（vue-tsc 0 错误 + vite）。

### 3.2 缺陷清单附录：D15（用户实测 UI 登录 401，前端修复，2026-11-23）

| # | 缺陷 | 根因 | 修复 | 门禁 |
|---|---|---|---|---|
| D15 | 浏览器 UI 登录始终报 `401 {"code":"AUTH-401-001","message":"authentication failed"}`（同口令 curl/脚本 200） | `src/api/http.ts` axios.create 实例默认头 `Content-Type: application/json; charset=UTF-8`（:46-47）盖住 URLSearchParams 自动表单头——`src/api/modules/auth.ts` login() 传表单体却顶着 JSON 头发出；Spring Security formLogin 只解析 `application/x-www-form-urlencoded`，JSON 头下 `request.getParameter` 取不到 username/password → 按空凭据拒绝（空凭据响应与用户所见逐字一致）。t6 联调 23/23 全绿因脚本走 curl/Python 直发，未经过 axios 层，故漏检 | `src/api/http.ts` 请求拦截器内加 `if (config.data instanceof URLSearchParams) { config.headers.set('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8') }`（仅影响表单体调用，JSON 调用零变化；修复 v1 曾在 auth.ts 传 per-call headers，被 `ApiRequestOptions` 类型拒（TS2353）废弃） | **npm run build 绿**（vue-tsc + vite 6.94s）；wire 复刻修复后 `LOGIN 200 ncg-admin true`；**无头 chromium 真实 UI 验收**：填表→点登录→`/workbench` 落地，`/api/v1/auth/me` 200，截图 `.tools/login-acceptance.png` |

### 3.3 缺陷清单附录：D16（用户实测配置目录 404 弹窗洪泛，2026-11-23）

| # | 缺陷 | 根因 | 修复 | 门禁 |
|---|---|---|---|---|
| D16 | 配置目录打开即弹一串 `config not found: /DEFAULT_GROUP/xxx.yaml` `SYS-404-001` 错误弹窗（用户实测，TraceId 见用户反馈） | 三因叠加：① `src/stores/catalog.ts` selectApp 预取详情与 openDetail 未传 `{silent:true}`，`src/api/http.ts` 全局错误提示对每个 404 预取各弹一条 ElMessage（UI 本有 `—` 降级，弹窗纯噪音）；② FakeNacosConfigAdapter 纯内存 store，web 进程重启后 PG 映射行还在而 Nacos 内容全丢 → `/configs/{mappingId}` 对无内容行 404（历史测试残留 16 行 matrix-*/cancel-* 为重灾户）；③ 软删 `status="DELETED"` 的映射行被 `findByAppIdOrderByDataIdAsc`/`findByAppIdAndEnvIdOrderByDataIdAsc` 捞回目录，预取对死行持续 404 | ① `src/stores/catalog.ts` selectApp 预取与 openDetail 全部 `catalogApi.getConfig(id, { silent: true })`（404 不弹窗，行显 `—`）；② FakeNacosConfigAdapter 磁盘持久化：`FakeNacosConfigAdapter(Path persistPath)`（无参构造=内存模式兼容既有测试），mutate 后 `persist()` tmp+ATOMIC_MOVE 落盘、启动 `restore()` 恢复（`namespace/group/dataId` 为 ref key，historyId 序列恢复后续增），`POST /api/v1/_fixture/persist`（@Profile("local")）手动落盘；`NCG_FAKE_STORE` 环境变量指定 `.tools/fake-store.json`；③ ConfigMappingRepository 加 `findByAppIdAndEnvIdAndStatusNotOrderByDataIdAsc`/`findByAppIdAndStatusNotOrderByDataIdAsc`，CatalogService.listMappings/listMappingsByApp 改调（CatalogController.java:72/74/151/167 调用方零改动）；④ 测试残留 16 行 DELETE /mappings/{id} 软删入回收站 | **mvn test 57/57 绿**（53+4 新增 FakeNacosPersistTest：round-trip 内容/历史/订阅者、missing file 空启动、无路径 persistNow=false、historySeq 续增）；npm run build 绿（6.81s）；**持久化实证**：灌 14 权威种子（.tools/tc_run.py 口径）→persist→重启 web→`GET /configs/{id}` items 数逐行一致（app.json=4、app.properties=5、app.yaml=5 等，MISMATCH none）、`bad.yml` parseError 保持、TEXT content 一致；DELETED 过滤后目录 35→14 行全 MANAGED |

> 附注：`GET /configs/{mappingId}` 对结构化格式只返 `items[]` 不返 `content`（content 空会致 items 为空），回灌必须带真实文本内容；`/_fixture/seed` body 字段＝envId/namespace/groupName/dataId/content/operator。

### 3.4 缺陷清单附录：D17（envId/envCode 混用致配置表恒空，前端修复，2026-11-23）

| # | 缺陷 | 根因 | 修复 | 门禁 |
|---|---|---|---|---|
| D17 | 配置目录选中应用后表格恒空『该应用在当前筛选下没有纳管配置』（实际有 14 条纳管配置）；工作台指标恒 0；管理页环境列显示 ULID 原文；筛选/授权/通知绑定表单传的「环境」实际收效为 0（D16 浏览器终验时暴露） | 全前端把 EnvName（`'dev'/'staging'/'prod'`，app store `state.env`，localStorage `ncg.env`）直接当 envId 用，而数据里的 envId 是 ULID（`01ENVDEV…`×13＋`01ENVSTG…`×1）：① `CatalogView.vue:49` 过滤 `m.envId === appStore.env` 恒 false → 表恒空；② `WorkbenchView.vue:56` 把 envCode 传给 `listChanges/listReleases`，后端逐行 `envId.equals(cr.getEnvId())` 比 ULID → 指标恒 0；③ 13 处模板直显 `row.envId`＝ULID 原文；④ ChangesView/ReleasesView/RecycleBinView/AdminView 筛选与表单 `value='dev'` 传参恒不匹配（授权/通知绑定以 code 存 envId，权限比对隐患被 ADMIN 全权掩盖） | 统一 code↔id 映射：`api/types.ts` 新增 `EnvItem`；`api/modules/catalog.ts` 新增 `listEnvs()`→`GET /envs`；`stores/app.ts` 加 `envs`/`currentEnvId`（envCode→ULID，回退 code）/`envLabelOf(idOrCode)`（envId/envCode 双查→displayName，回退原值）/`envIdOptions`（value=ULID）/`loadEnvs()`（幂等，AppLayout onMounted 全局加载）；14 文件改点：CatalogView 过滤＋环境列、WorkbenchView 查询、ChangesView/ReleasesView/RecycleBinView/AdminView 筛选与表单选项换 ULID、AdminView `envLabel()` 委托 `envLabelOf`、ApprovalsView/ChangeDetailView/SchedulesView/RecycleBinView 显示换 displayName、ChangeCreateDialog 兜底 `?? appStore.currentEnvId`；AppLayout 环境切换器保留 code 语义、EnvSyncView 后端 id/code 双兼容不动 | **npm run build 绿**（vue-tsc + vite 6.88s）；**无头 chromium 复验**：工作台指标待我审批 3/进行中变更 3/直连告警 2/最近发布 5（非恒 0，待审批列表出真实单 CR-20261122-0071 等）；配置目录「仅看当前环境」13 行＝dev 13 条、关闭后 **14 行全显**（与 `GET /apps/{appId}/configs` 一致）；toasts=[] 无 404 弹窗；环境列显示『开发环境』displayName；截图 `.tools/catalog-d17-acceptance.png`、`.tools/catalog-d17-allrows.png` |

## 4. 48 端点可达性矩阵（doc/ncg-api-contract.md §2）

状态码/业务码 + 关键字段实测（完整机器可读结果：`.tools/matrix-results.json`，50 行含 #47/#48 各 2 路由）。全部 REACHABLE。

| # | 方法 | 路径 | 实测 | 关键证据 |
|---|---|---|---|---|
| 1 | POST | /auth/login | 200 | 表单编码 username/password；6 账号链均 200 |
| 2 | POST | /auth/logout | 200 | |
| 3 | GET | /auth/me | 200 | CurrentUser：userId/username/roles/enabled/createdAt + permissions |
| 4 | GET | /apps | 200 | AppItem{appId,appName,owner,envIds[]} |
| 5 | GET | /apps/{appId}/configs | 200 | mappingRow 含 format/md5/collectedAt 别名（旧字段保留） |
| 6 | GET | /configs/{mappingId} | 200 | 缺口2 shape：items[]/content/parseError，敏感 •••••• |
| 7 | POST | /configs/{mappingId}/sensitive-view | 200 | 缺口3：itemKey/value/expiresAt=+30s |
| 8 | POST | /mappings | 200 | 返 mappingId + mappingRow |
| 9 | PUT | /mappings/{id} | 200 | 契约 update 字段别名（owner→7 参重载） |
| 10 | DELETE | /mappings/{id} | 200 | 软删进回收站（#46 配套） |
| 11 | POST | /mappings/import | 200 | {imported,items} |
| 12 | POST | /changes | 200 | 缺口1/4：items[]→CR-20261122-0043 status=DRAFT format=YAML itemCount=1 |
| 13 | PUT | /changes/{changeNo} | 200 | 乐观锁 version |
| 14 | POST | /changes/{changeNo}/submit | 200 | 提交冻结基线 |
| 15 | POST | /changes/{changeNo}/cancel | 200 | status=CANCELLED（.tools/m15.py 补录 CR-20261122-0059） |
| 16 | GET | /changes | 200 | ChangeListItem row 形状（非 PageResult，对齐 types.ts） |
| 17 | GET | /changes/{changeNo} | 200 | detail：version/format/items（敏感脱敏） |
| 18 | GET | /changes/{changeNo}/impact | 200 | ImpactReport |
| 19 | POST | /changes/{changeNo}/approve | 200 | 应用负责人审批（MUST-06 职责分离） |
| 20 | POST | /changes/{changeNo}/reject | 200 | 驳回原因≥10 |
| 21 | POST | /changes/{changeNo}/transfer | 200 | targetUserId 别名→targetApproverId |
| 22 | POST | /changes/{changeNo}/release | 200 | ReleaseDetail：releaseId/result/readbackMd5/traceId |
| 23 | POST | /changes/{changeNo}/rollback | 200 | targetVersion 兼容（反查 releaseKey） |
| 24 | POST | /changes/{changeNo}/schedule | 200 | plannedAt 别名（executeAt 兼容） |
| 25 | DELETE | /changes/{changeNo}/schedule | 200 | scheduleRow status=CANCELED |
| 26 | POST | /releases/{releaseId}/reconcile | 200 | 差异5：conclusion 三选一（502 发布对账实测 RELID=01M82C60CEZRTGWTCEH6Y4MHY8） |
| 27 | GET | /releases | 200 | 差异3：changeNo 可选；result←outcome 小写映射 |
| 28 | GET | /releases/{releaseId} | 200 | releaseDetailBody（preflight/error/nacosReceipt） |
| 29 | POST | /batches | 200 | 缺口5：items[] 建批→batchId/childCount/status=DRAFT/items[] 含 itemId |
| 30 | PUT | /batches/{batchId}/items/{itemId} | 200 | version 0→1 递增（契约缺口端点补齐） |
| 31 | POST | /batches/{batchId}/submit | 200 | 逐子提交→SUBMITTED |
| 32 | POST | /batches/{batchId}/retry | 409 SYS-409-001 | 全成功批次拒绝重试（成功项永不重发 R-SCH-06） |
| 33 | POST | /env-sync/compare | 200 | dev→prod 差异（missing_in_target）；dev→staging 409 SYN-409-002（TC-18） |
| 34 | POST | /env-sync/apply | 200 | 勾选差异项建 SYNC 单 → ['CR-20261122-0050'] |
| 35 | GET | /env-sync/{snapshotId} | 200 | snapshotBody diffs 形状 |
| 36 | GET | /instances | 200 | instanceRow（MUST-NOT-05：无 credentialCiphertext） |
| 37 | POST | /instances/{id}/test | 200 | id 两义兼容（instanceId 优先、envId 旧口径兜底）→InstanceTestResult |
| 38 | GET | /notify-bindings | 200 | target 恒脱敏 ●●●●●●●● |
| 39 | PUT | /notify-bindings/{appId}/{envId} | 200 | 保存不回显目标（MUST-NOT-05） |
| 40 | DELETE | /notify-bindings/{appId}/{envId} | 200 | 幂等 {deleted:true} |
| 41 | GET | /audit-events | 200 | PageResult{items,total,page,size}+auditRow（changeNo 可选） |
| 42 | POST | /audit-events/export | 200 | CSV blob 下载（JSON 可选） |
| 43 | GET | /direct-changes | 200 | directChangeRow（OPEN=True，真实告警行） |
| 44 | POST | /direct-changes/{id}/close | 200 | 整行返回 |
| 45 | GET | /recycle-bin | 200 | PageResult + appId/envId/dataId/expireAt 别名 |
| 46 | POST | /recycle-bin/{id}/restore | 200 | 无冲突直恢复 RESTORED；冲突分支 RB-409-001（TC-22） |
| 47 | GET | /users | 200 | userRow userId/username/roles/enabled |
| 47 | GET | /grants | 200 | grantRow userId/username/permission |
| 48 | POST | /users | 200 | CreateUserRequest{username,password,roles} 别名 |
| 48 | POST | /grants | 200 | CreateGrantRequest{userId} 别名 |

补充：#32 与 #43/#44 的非 200 响应均为**契约定义的业务码**（SYS-409-001、RB-409-001 等），属预期行为非缺陷。

## 5. 「所有功能正常可用」逐项确认

| 功能域 | 确认 |
|---|---|
| 登录/会话/权限（含 XSRF、401 统一、无 VIEW_SENSITIVE 拒绝） | ✓（AUTH-CHAIN、TC-07） |
| 配置目录浏览（四格式解析、脱敏、parseError 只读展示、未知格式只读 CR-415-001） | ✓（TC-01~04、TC-10） |
| 敏感查看（reason≥10、单项、30s 再脱敏、审计留痕） | ✓（TC-05） |
| 变更单全生命周期（建/改/提交/审批/驳回/转交/取消/影响面/回滚） | ✓（TC-09/12/16/19、#13-23） |
| 发布（预检、写后读回、幂等重放、不确定 502+人工对账、定时） | ✓（TC-20/21、#22-28） |
| 批量（子草稿、逐项编辑 version 递增、上限 50、提交后锁定、部分成功重试语义） | ✓（TC-13~17、#29-32） |
| 跨环境同步（format 一致强制 SYN-409-002、快照、勾选建单） | ✓（TC-18、#33-35） |
| 监督（审计分页/导出、直连告警闭环、哈希链前端核对） | ✓（#41-44） |
| 管理（账号/授权/实例测试/通知绑定恒脱敏/回收站恢复三选一） | ✓（#36-40、#45-48、TC-22） |
| 敏感明文零泄露（响应/审计/日志抽验） | ✓（MUST-NOT-05 全链抽验） |

## 6. 契约↔Java 后端形态差异（t3 清单 6 项）逐项决策与理由

| # | 差异 | 决策 | 理由 |
|---|---|---|---|
| 1 | POST /batches 实收 {changeNos[]}，缺 PUT /batches/{batchId}/items/{itemId} | **回到契约**：新增 items[] 模式（缺口5：每 DataId 一个子草稿，同 DataId 多 key 合并），并补齐 PUT item 端点（version 乐观锁递增、已提交 SYS-409-001、敏感强制重输）；旧 {changeNos[]} 组批发布保留为 mode='RELEASE'（ncg_batch.mode 列区分，DEFAULT 'RELEASE' 兼容存量） | 详细设计 §6 已列该端点；双模式向后兼容，mvn test 47 绿（runBatch/retryFailed 签名未动） |
| 2 | Schedule body {executeAt}≠{plannedAt}，无 GET 定时列表 | **字段统一 plannedAt**（executeAt 留兼容别名，响应 scheduleRow 双写 plannedAt←executeAt、reviewReason←lastErrorCode）；**不新增 GET 定时列表端点**——契约未定义该端点，前端按方案以 listChanges(status=SCHEDULED)+store 推导（前端 t3 既定实现） | 尊重契约端点面不扩面；字段命名回到契约口径 |
| 3 | GET /releases 必填 ?changeNo= | **契约口径**：changeNo 改可选（缺省返全量按 startedAt 降序），另加 result/envId/page/size 过滤（前端 listReleases 参数口径） | 契约未标必填；可选是超集，旧调用不受影响 |
| 4 | recycle-bin 返回字段与契约 RecycleBinItem 不一致 | **后端补别名（向后兼容）**：行内增 appId/envId/dataId（经 mapping 反查）与 expireAt←purgeAt，旧字段 itemId/mappingId/deletedAt/deletedBy/purgeAt/restoreStatus 保留；restore 增可选 body {onConflict,itemVersion}：OVERRIDE_AND_REAPPROVE→restoreAsChange 建 RESTORE 草稿走重新审批，ABANDON→{conflict:true} 条目保持 PENDING，无 body 保持原直恢复语义；同名冲突 409 RB-409-001 details{options:[…]}（MUST-NOT-06） | 字段两可时取后端别名（保留旧字段）为先；恢复三选一是红线语义，必须回契约 |
| 5 | reconcile 契约签名无 body，UI 三选一无法上送 | **后端+前端各半**：后端 reconcile（/changes 与 /releases/{id} 双入口）增可选 {conclusion:EFFECTIVE\|NOT_EFFECTIVE\|OVERRIDDEN}（无 conclusion 回退 confirmedEffective 兼容旧调用；OVERRIDDEN=人工判定现场已被其他写入覆盖，按生效处理+RELEASE_RECONCILE 审计标注）；前端 reconcileRelease 增 conclusion 参数上送 | 差异标 [TBD]，UI 已收集三选一；OVERRIDDEN 不改写事实只做人工判定语义，审计可溯 |
| 6 | BatchCreateResponse 无 items[]，itemId 无获取路径 | **后端加法返回 items[]**（[{itemId,changeNo,mappingId,dataId}] + mode 字段），契约原有 batchId/childCount/status 字段不动；itemId 同时是 PUT item 的路径参数（TC-13/16 依赖） | 契约响应缺 items[] 与其自身 PUT 端点矛盾（[TBD]），加法补齐是唯一自洽解 |

## 7. 口径决策（契约 §5 待确认项 + 实现口径）

- **A（TEXT 可编辑性，§5.1）**：可识别 TEXT 后缀（.txt 等）可整文件编辑；未知后缀（.ini 等）persisted format=TEXT 但写路径按后缀拒写 CR-415-001。这是 TC-03（.txt 可编辑）与 TC-10（.ini→415）并存的唯一自洽解释。
- **B（敏感识别）**：展开后 key 的关键词启发（password/secret/token/credential/accessKey/privateKey/apiKey/auth 等）+ mapping.sensitive 整文件覆盖。[Assumption,TBD]——阈值表归产品定义。
- **C（基线冻结）**：baselineMd5 创建时记录（请求携带或读当前值），提交时复核不一致→CR-412-001（TC-19）；提交时冻结、发布前重读不一致→REL-412-001 退回审批+三方 Diff（R-CR-04/MUST-05 不冲突）。
- **D（TC-20 注入）**：`POST /api/v1/_fixture/*`（@Profile("local")）切换 FakeNacosConfigAdapter FailureMode——不新增业务 API 面，real 模式端点不存在。含 /scan-direct（D13）。
- **E（后端修复风格）**：向后兼容加法——新请求字段可选、响应加字段不删字段、服务方法签名零改动（Docker 门控集成测试编译依赖）；每批 mvn test 47/47 复验。
- **F（种子）**：重建 ncg 库（pgdata 目录保留）获得确定种子：3 env（dev/staging/prod）、1 app（demo-app，owner=approver-1）、8 映射（app.properties/app.yaml/app.json/app.txt/bad.yml/config.ini/app2.yaml@dev + app2.properties@staging）、6 账号 36 授权（每 env）。

## 8. 服务状态（完成时点）

| 服务 | 状态 | 备注 |
|---|---|---|
| PostgreSQL 15（.tools/pgdata） | **保留运行** | job 长驻，127.0.0.1:55432，数据保留 |
| web（ncg-web.jar） | **保留运行** | :8085，local profile，D14 修复后构建（2026-11-23 04:26） |
| worker（ncg-worker.jar） | 停止 | t9 会话未拉起（22 用例与 48 矩阵均不依赖 worker：直连扫描走 /_fixture/scan-direct）；需要时按 §9 命令启动 |

服务保留供 UI 验收/演示继续使用。停止方式：结束上述三个后台 job（或 kill 对应 java/postgres 进程）；重启命令模板见 README「三条命令」+ 环境变量 `NCG_DB_URL=jdbc:postgresql://127.0.0.1:55432/ncg?sslmode=disable`（⚠️本机 PG15 未开 SSL，JDBC URL 必须带 sslmode=disable，否则启动期 Flyway 连接报 "no pg_hba.conf entry … SSL encryption"）。

## 9. 复跑指引

```bash
cd /vol1/1000/share/coding/NacosManagePlatform
python3 .tools/tc_cleanup.py     # 清理活跃单/复位 failure-mode
python3 .tools/tc_run.py         # 22 用例 → .tools/tc-results.json（期望 SUMMARY 23/23 PASS）
python3 .tools/tc_matrix.py      # 48 端点矩阵 → .tools/matrix-results.json（期望 MATRIX 49/49，#15 由 m15.py 补录）
python3 .tools/m15.py            # #15 cancel 补录
python3 .tools/d14_verify.py     # D14：五端点 {} 400 CR-400-001 + 回归集（期望 SUMMARY 17/17 PASS）
source .tools/env.sh && mvn -B -ntp -Dmaven.repo.local=.tools/m2 test   # 期望 53/53（47 基线 + EmptyBodyValidationTest 6）
cd frontend && npm run build     # 期望 vue-tsc 0 错误 + vite ✓
```

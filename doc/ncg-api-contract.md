# TongNCS 配置治理平台 前后端联调接口清单

| 项目 | 内容 |
|---|---|
| 版本 | v1.2 |
| 日期 | 2026-11-22 |
| 依据 | 详细设计 v1.4 §3.5、§4.5、§5.2–§5.3、§6.1–§6.6；spec v1.4（原《前端交互设计》v1.1 已并入详细设计 §3.5） |
| 口径 | 48 个端点、39 条错误码；Base path `/api/v1` |
| 用途 | 前后端联调、Mock 对齐、可执行测试清单、验收自检 |

> 本清单是详细设计 §6.2 的可执行视图。路径、权限、幂等与错误码以详细设计为准；页面归属以详细设计 §3.5.2 为准（P-01～P-17 编号对照见 §4.1）。契约冲突时以详细设计为准，业务规则冲突时以 spec 为准。

## 1 通用约定

### 1.1 请求与认证

| 项 | 约定 |
|---|---|
| Base path | `/api/v1` |
| 内容类型 | `application/json; charset=UTF-8` |
| 认证 | `NCG_SESSION` Cookie（服务端会话） |
| CSRF | 写请求携带 `X-XSRF-TOKEN` 请求头 |
| TraceId | 请求可传 `X-Trace-Id`；未传由服务端生成并在响应回写 |
| 时间 | 接口统一 ISO-8601 UTC（`2026-11-22T08:15:00Z`）；前端按 `Asia/Shanghai` 展示 |
| 分页 | `page` 从 0 开始；`size` 默认 20、最大 100 |
| 环境上下文 | 由当前会话与页面顶栏环境切换共同确定；跨环境接口显式传源/目标环境 |

### 1.2 统一错误体

```json
{
  "code": "REL-412-001",
  "message": "基线已变化，请重新审批",
  "traceId": "7f3c...",
  "details": { "changeNo": "CR-20261121-0001" },
  "timestamp": "2026-11-21T10:00:00Z"
}
```

- 前端按 `code` 分支展示文案与动作，不解析 `message`。
- 未识别错误统一按 `SYS-500-001` 处理，并保留 `traceId` 供复制。
- 完整 39 条错误码以 spec E.3 与详细设计 §6.6 为准。

### 1.3 配置格式与内容契约

| 格式 | 后缀 | 读取返回 | 写入方式 | 说明 |
|---|---|---|---|---|
| `PROPERTIES` | `.properties` | `items[]` | `items[]` 或 `content`（整文件替换） | `key=value` / `key:value` 逐行解析 |
| `YAML` | `.yaml` / `.yml` | `items[]`（点路径） | `items[]` 或 `content`（整文件替换） | 嵌套 key 展开为点路径 |
| `JSON` | `.json` | `items[]`（点路径） | `items[]` 或 `content`（整文件替换） | 嵌套规则同 YAML |
| `TEXT` | 其他后缀 | `content` | `content`（仅全文编辑） | 不解析，整文件视为一个内容项 |
| 无法识别 | 未知后缀 | 只读 | 禁止写入（`CR-415-001`） | 纳管时固化，不支持运行期变更 |

- `format` 在纳管时按后缀写入并固化；`ConfigMapping.format` 不可更新。
- 结构化变更以 `items[]` 为主契约；`CREATE`、`TEXT` 或整文件替换才使用 `content`；两者互斥。
- 解析失败返回 `CR-422-001` 与行号/列号；格式不支持返回 `CR-415-001`。

### 1.4 幂等规则

| 场景 | 幂等依据 | 重复请求表现 |
|---|---|---|
| 发布 | `release_key`（变更单号 + 目标版本） | 返回 200 与既有发布记录（`REL-409-002`） |
| 恢复 | `item_id + target_version` | 返回既有结果，不重复写入 |
| 审批 / 驳回 | 条件更新 + 唯一审批记录 | 返回当前状态，不重复写审批记录 |
| 扫描 / 通知 | 数据库唯一键 | 不重复告警、不重复发送 |
| 批量提交 / 重试 | 状态机 + 条件更新 | 已提交子项不重发 |

## 2 接口清单

### 2.1 认证与配置目录（1–11）

| # | 模块 | 方法 | 路径 | 用途 | 权限 | 幂等 | 主要错误 | 前端页面 / 调用时机 |
|---|---|---|---|---|---|---|---|---|
| 1 | auth | POST | `/auth/login` | 登录并创建会话 | 匿名 | 否 | `AUTH-401-001`、`AUTH-401-002` | P-01 登录页提交 |
| 2 | auth | POST | `/auth/logout` | 注销当前会话 | 已登录 | 是 | `AUTH-401-001` | 顶栏退出 |
| 3 | auth | GET | `/auth/me` | 当前用户、角色与权限点 | 已登录 | 是 | `AUTH-401-001` | 应用启动、路由守卫 |
| 4 | catalog | GET | `/apps` | 应用列表 | VIEW | 是 | — | P-03 左侧应用树 |
| 5 | catalog | GET | `/apps/{appId}/configs` | 应用下 DataId 配置文件列表 | VIEW | 是 | `SYS-404-001` | P-03 配置表 |
| 6 | catalog | GET | `/configs/{mappingId}` | 读取 DataId 内容与配置项 `items[]` | VIEW | 是 | `SYS-404-001`、`CON-502-001` | P-03 详情抽屉；P-05 载入当前值 |
| 7 | catalog | POST | `/configs/{mappingId}/sensitive-view` | 按 `itemKey` 查看单项明文 | VIEW_SENSITIVE | 否 | `AUTHZ-403-001`、`CR-400-001`、`SYS-404-001` | P-03 敏感值弹窗 |
| 8 | catalog | POST | `/mappings` | 纳管 TongNCS 配置为映射 | ADMIN | 否 | `CAT-409-001` | 管理员纳管（P0 前端未单列页面） |
| 9 | catalog | PUT | `/mappings/{id}` | 更新映射元数据（不含 `format`） | ADMIN | 否 | `CAT-409-001`、`SYS-409-001` | 管理员纳管（P0 前端未单列页面） |
| 10 | catalog | DELETE | `/mappings/{id}` | 解除纳管 | ADMIN | 否 | `SYS-404-001` | 管理员纳管（P0 前端未单列页面） |
| 11 | catalog | POST | `/mappings/import` | 批量导入映射 | ADMIN | 否 | `CAT-409-002` | 管理员纳管 / 运维脚本 |

### 2.2 变更与审批（12–21）

| # | 模块 | 方法 | 路径 | 用途 | 权限 | 幂等 | 主要错误 | 前端页面 / 调用时机 |
|---|---|---|---|---|---|---|---|---|
| 12 | change | POST | `/changes` | 创建变更草稿（`items[]` 或 `content`） | EDIT | 否 | `CR-400-001`、`CR-409-001`、`CR-415-001`、`CR-422-001` | P-05 新建；P-03 发起变更 |
| 13 | change | PUT | `/changes/{changeNo}` | 修改草稿内容 | EDIT | 否 | `CR-400-001`、`CR-415-001`、`CR-422-001`、`SYS-409-001` | P-06 草稿编辑 |
| 14 | change | POST | `/changes/{changeNo}/submit` | 提交审批并冻结基线 | SUBMIT | 否 | `CR-412-001`、`CR-413-001` | P-06 提交 |
| 15 | change | POST | `/changes/{changeNo}/cancel` | 取消变更单 | SUBMIT / PUBLISH | 是 | `SYS-409-001` | P-06 取消 |
| 16 | change | GET | `/changes` | 变更单列表与筛选 | VIEW | 是 | — | P-02 / P-04 列表 |
| 17 | change | GET | `/changes/{changeNo}` | 变更详情 | VIEW | 是 | `SYS-404-001` | P-06 详情 |
| 18 | impact | GET | `/changes/{changeNo}/impact` | 影响面与风险 | VIEW | 是 | `IMP-409-001` | P-05 提交前预览；P-06 影响面页签 |
| 19 | approval | POST | `/changes/{changeNo}/approve` | 审批通过 | APPROVE | 否 | `AUTHZ-403-002`、`AUTHZ-403-003`、`APR-409-001` | P-07 通过 |
| 20 | approval | POST | `/changes/{changeNo}/reject` | 驳回（理由 ≥ 10 字） | APPROVE | 否 | `APR-422-001` | P-07 驳回 |
| 21 | approval | POST | `/changes/{changeNo}/transfer` | 转交审批 | APPROVE | 否 | `AUTHZ-403-001` | P-07 转交 |

### 2.3 发布、回滚与定时（22–28）

| # | 模块 | 方法 | 路径 | 用途 | 权限 | 幂等 | 主要错误 | 前端页面 / 调用时机 |
|---|---|---|---|---|---|---|---|---|
| 22 | release | POST | `/changes/{changeNo}/release` | 执行发布（写 TongNCS 并读回） | PUBLISH | 是 | `REL-403-001`、`REL-409-001`、`REL-412-001`、`REL-422-001`、`REL-502-001`、`REL-503-001` | P-08 执行发布 |
| 23 | release | POST | `/changes/{changeNo}/rollback` | 回滚到历史版本 | ROLLBACK | 是 | `RB-410-001`、`REL-403-001`、`REL-502-001`、`REL-503-001` | P-06 / P-08 回滚 |
| 24 | scheduler | POST | `/changes/{changeNo}/schedule` | 创建定时发布任务 | PUBLISH | 否 | `SCH-409-001` | P-10 定时设置 |
| 25 | scheduler | DELETE | `/changes/{changeNo}/schedule` | 取消定时发布任务 | PUBLISH | 是 | `SCH-409-001` | P-10 取消 |
| 26 | release | POST | `/releases/{releaseId}/reconcile` | 不确定结果人工对账 | PUBLISH | 是 | `REL-502-001` | P-08 对账队列 |
| 27 | release | GET | `/releases` | 发布记录列表 | VIEW | 是 | — | P-02 / P-08 发布记录 |
| 28 | release | GET | `/releases/{releaseId}` | 发布记录详情 | VIEW | 是 | `SYS-404-001` | P-08 结果详情 |

### 2.4 批量与跨环境同步（29–35）

| # | 模块 | 方法 | 路径 | 用途 | 权限 | 幂等 | 主要错误 | 前端页面 / 调用时机 |
|---|---|---|---|---|---|---|---|---|
| 29 | batch | POST | `/batches` | 创建批量父单与 DataId 子草稿 | EDIT | 否 | `CR-400-001`、`CR-409-001`、`CR-415-001`、`CR-422-001`、`BAT-413-001` | P-03 多选批量；P-11 新建 |
| 30 | batch | PUT | `/batches/{batchId}/items/{itemId}` | 草稿阶段编辑子项配置值 | EDIT | 否 | `CR-400-001`、`CR-415-001`、`CR-422-001`、`SYS-409-001` | P-11 草稿“编辑值” |
| 31 | batch | POST | `/batches/{batchId}/submit` | 提交批量父单 | SUBMIT | 否 | `BAT-413-001` | P-11 提交 |
| 32 | batch | POST | `/batches/{batchId}/retry` | 只重试失败子项 | PUBLISH | 是 | `SYS-409-001` | P-11 重试失败项 |
| 33 | envsync | POST | `/env-sync/compare` | 三环境内容对比 | VIEW | 是 | `SYN-409-002` | P-09 对比 |
| 34 | envsync | POST | `/env-sync/apply` | 生成目标环境变更单 | EDIT | 否 | `SYN-409-001`、`SYN-409-002` | P-09 生成目标单 |
| 35 | envsync | GET | `/env-sync/{snapshotId}` | 查看同步快照 | VIEW | 是 | `SYS-404-001` | P-09 快照详情 |

### 2.5 管理与监督（36–48）

| # | 模块 | 方法 | 路径 | 用途 | 权限 | 幂等 | 主要错误 | 前端页面 / 调用时机 |
|---|---|---|---|---|---|---|---|---|
| 36 | catalog | GET | `/instances` | TongNCS 实例列表与健康 | ADMIN | 是 | — | P-15 实例列表 |
| 37 | catalog | POST | `/instances/{id}/test` | 连接测试 | ADMIN | 是 | `CON-502-001` | P-15 连接测试 |
| 38 | notification | GET | `/notify-bindings` | 应用 × 环境通知绑定列表 | ADMIN | 是 | — | P-16 绑定列表 |
| 39 | notification | PUT | `/notify-bindings/{appId}/{envId}` | 保存通知绑定 | ADMIN | 否 | `CR-400-001`、`SYS-404-001` | P-16 保存 |
| 40 | notification | DELETE | `/notify-bindings/{appId}/{envId}` | 删除通知绑定 | ADMIN | 是 | `SYS-404-001` | P-16 解绑 |
| 41 | audit | GET | `/audit-events` | 审计事件查询 | VIEW_AUDIT | 是 | — | P-13 审计流 |
| 42 | audit | POST | `/audit-events/export` | 导出审计事件 | EXPORT_AUDIT | 否 | `AUD-500-001` | P-13 导出 |
| 43 | audit | GET | `/direct-changes` | 直连变更告警列表 | VIEW_AUDIT | 是 | — | P-02 / P-13 直连告警 |
| 44 | audit | POST | `/direct-changes/{id}/close` | 处置直连告警 | VIEW_AUDIT | 是 | `SYS-404-001` | P-13 处置 |
| 45 | release | GET | `/recycle-bin` | 回收站列表（30 天） | PUBLISH | 是 | — | P-12 回收站 |
| 46 | release | POST | `/recycle-bin/{id}/restore` | 恢复配置 | PUBLISH | 是 | `RB-409-001`、`RB-410-001`、`REL-503-001` | P-12 恢复 / 同名冲突弹窗 |
| 47 | auth | GET | `/users`、`/grants` | 账号与授权查询 | ADMIN | 是 | — | P-14 账号与授权 |
| 48 | auth | POST | `/users`、`/grants` | 新建账号、授予权限 | ADMIN | 否 | `AUTHZ-403-001` | P-14 新建 / 授权 |

> 第 47、48 行沿用详细设计 §6.2 的合并写法，分别对应账号与授权两个路由；按文档口径计为 2 个端点，实际路由模式为 4 个。

## 3 五个缺口接口的完整示例

### 3.1 缺口 1：`ConfigMapping.format` 持久化

`format` 在纳管时由 `data_id` 后缀识别并写入，之后不可更新。前端从 `GET /apps/{appId}/configs`、`GET /configs/{mappingId}` 与 `POST /changes` 响应中读取，用于选择编辑形态。

| 字段 | 类型 | 取值 / 说明 |
|---|---|---|
| `format` | string | `PROPERTIES` / `YAML` / `JSON` / `TEXT` |
| 后缀映射 | — | `.properties` → PROPERTIES；`.yaml` / `.yml` → YAML；`.json` → JSON；其余 → TEXT |
| 不可变性 | — | 纳管后不可更新；改后缀需解除纳管后重新纳管 |

创建变更响应中的格式回显：

```json
{
  "changeNo": "CR-20261121-0001",
  "status": "DRAFT",
  "riskLevel": "MEDIUM",
  "version": 0,
  "format": "YAML",
  "itemCount": 2
}
```

### 3.2 缺口 2：`GET /configs/{mappingId}` 返回 `items[]`

请求：

```http
GET /api/v1/configs/01J8Z6M3K4P5Q7R9S2T3V4W5X6
Cookie: NCG_SESSION=...
X-Trace-Id: 7f3c...
```

PROPERTIES 响应（敏感值默认脱敏）：

```json
{
  "mappingId": "01J8Z6M3K4P5Q7R9S2T3V4W5X6",
  "dataId": "payment-datasource.properties",
  "format": "PROPERTIES",
  "md5": "3ad9c0f1...",
  "collectedAt": "2026-11-22T08:15:00Z",
  "items": [
    {
      "key": "spring.datasource.url",
      "value": "jdbc:mysql://pay-db:3306/pay",
      "valueType": "STRING",
      "sensitive": false
    },
    {
      "key": "spring.datasource.password",
      "value": "••••••",
      "valueType": "STRING",
      "sensitive": true
    }
  ]
}
```

TEXT 响应（只返回 `content`，不返回 `items`）：

```json
{
  "mappingId": "01J8Z6M3K4P5Q7R9S2T3V4W5X6",
  "dataId": "payment-notes.txt",
  "format": "TEXT",
  "md5": "7c1e2a9b...",
  "collectedAt": "2026-11-22T08:15:00Z",
  "content": "支付服务维护说明，整文件编辑。"
}
```

解析失败响应（仍为 200，只读展示，不阻断浏览）：

```json
{
  "mappingId": "01J8Z6M3K4P5Q7R9S2T3V4W5X6",
  "dataId": "payment-trace.yml",
  "format": "YAML",
  "md5": "9f2c4d1e...",
  "collectedAt": "2026-11-22T08:15:00Z",
  "items": [],
  "parseError": {
    "line": 12,
    "column": 5,
    "message": "mapping values are not allowed here"
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `mappingId` | string | 映射 ID |
| `dataId` | string | TongNCS 配置文件名，含后缀 |
| `format` | string | `PROPERTIES` / `YAML` / `JSON` / `TEXT` |
| `md5` | string | 采集时的内容 MD5 |
| `collectedAt` | string | 采集时间，UTC ISO-8601 |
| `items[]` | array | 结构化格式的配置项；TEXT 不返回 |
| `items[].key` | string | 展开后的点路径；PROPERTIES 保持原 key |
| `items[].value` | string | 当前值；敏感项为 `••••••` |
| `items[].valueType` | string | `STRING` / `NUMBER` / `BOOLEAN` / `JSON` |
| `items[].sensitive` | boolean | 是否敏感项 |
| `content` | string | TEXT 格式的整文件内容 |
| `parseError` | object | `line` / `column` / `message`；解析失败时出现 |

### 3.3 缺口 3：敏感值按 `itemKey` 查看

请求：

```http
POST /api/v1/configs/01J8Z6M3K4P5Q7R9S2T3V4W5X6/sensitive-view
Content-Type: application/json
X-XSRF-TOKEN: ...
Cookie: NCG_SESSION=...
```

```json
{
  "itemKey": "spring.datasource.password",
  "reason": "排查连接失败，需核对密码"
}
```

响应：

```json
{
  "itemKey": "spring.datasource.password",
  "value": "P@ssw0rd_pay_2026",
  "expiresAt": "2026-11-22T08:16:30Z"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `itemKey` | string | 是 | 要查看明文的配置项 key |
| `reason` | string | 是 | 查看原因，至少 10 字；写审计 |
| `value` | string | — | 仅返回该项明文 |
| `expiresAt` | string | — | 30 秒后前端重新脱敏 |

前端行为：只保留当前项明文；切换配置项或离开页面立即脱敏；不写入本地缓存与前端状态持久化。

### 3.4 缺口 4：`POST /changes` 以 `items[]` 为主契约

请求（UPDATE + 结构化格式）：

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
{
  "changeNo": "CR-20261121-0001",
  "status": "DRAFT",
  "riskLevel": "MEDIUM",
  "version": 0,
  "format": "YAML",
  "itemCount": 2
}
```

请求（CREATE / TEXT / 整文件替换，使用 `content`）：

```json
{
  "appId": "01J...",
  "envId": "01J...",
  "mappingId": "01J...",
  "changeType": "CREATE",
  "title": "新增支付日志配置",
  "reason": "接入统一日志采集，需要新增配置文件",
  "content": "log.level: INFO\nlog.retentionDays: 7\n",
  "plannedAt": "2026-11-22T02:00:00Z"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `appId` / `envId` | string | 是 | 目标应用与环境 |
| `mappingId` | string | 是 | 目标 DataId 映射 |
| `changeType` | string | 是 | `CREATE` / `UPDATE` / `DELETE` |
| `title` | string | 是 | 变更标题 |
| `reason` | string | 是 | 变更原因，至少 10 字 |
| `items[]` | array | 条件 | 结构化变更；与 `content` 互斥 |
| `items[].key` | string | 是 | 展开后的点路径；PROPERTIES 使用原 key |
| `items[].value` | string | 是 | 新值 |
| `items[].valueType` | string | 否 | 缺省 `STRING` |
| `content` | string | 条件 | CREATE / TEXT / 整文件替换；与 `items` 互斥 |
| `baselineMd5` | string | 否 | 创建时记录，提交时复核；不一致 `CR-412-001` |
| `plannedAt` | string | 是 | 计划发布时间 |

规则：

- `items` 与 `content` 必须二选一；同时出现或同时缺失返回 `CR-400-001`。
- `UPDATE` 的结构化格式使用 `items`；后端读取当前 DataId、应用变更并序列化整个文件。
- `CREATE`、`TEXT` 或整文件替换使用 `content`。
- 解析失败返回 `CR-422-001` 与行号；格式不支持返回 `CR-415-001`。

### 3.5 缺口 5：批量逐项值与草稿编辑值

批量创建（每个 DataId 一个子项）：

```http
POST /api/v1/batches
Content-Type: application/json
```

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
{
  "batchId": "01J...",
  "childCount": 2,
  "status": "DRAFT"
}
```

草稿阶段编辑子项值：

```http
PUT /api/v1/batches/{batchId}/items/{itemId}
Content-Type: application/json
```

```json
{
  "version": 3,
  "items": [
    { "key": "payment.circuit.threshold", "value": "30", "valueType": "NUMBER" }
  ]
}
```

```json
{
  "itemId": "01J...",
  "status": "DRAFT",
  "version": 4,
  "itemCount": 2
}
```

规则：

- 每个 DataId 生成一个 `ChangeRequest` 子草稿；同一 DataId 内多个 key 合并为一个子项。
- 任一所选 DataId 已有进行中变更单时整批拒绝，返回 `CR-409-001`。
- 未填任何新值返回 `CR-400-001`；超过上限返回 `BAT-413-001`。
- 子项或父单已提交时 `PUT /batches/{batchId}/items/{itemId}` 返回 `SYS-409-001`。
- 修改后重算 `content_ciphertext`、`content_sha256` 与 Diff；敏感项必须重新输入新值，不回显旧明文。

## 4 五个缺口：前端交互要求 vs 后端契约核对

> 前端交互要求取自原《前端交互设计》v1.1（已并入详细设计 §3.5，下文“前端设计 §x.y”均为原文档章节号，仅供追溯）；页面归属与交互以详细设计 §3.5 为准。

| # | 缺口 | 详细设计依据 | 前端交互依据（原章节号） | 核对点 | 结论 |
|---|---|---|---|---|---|
| 1 | `ConfigMapping.format` 持久化 | §5.2 表清单、§5.3 `ConfigMapping` 字段、§4.5 格式表 | §5.1 步骤 1/4、§6.2 `CR-415-001`、§9、§10 | 前端携带并展示 `format`；后缀识别；格式不可变；不支持只读 | 一致 |
| 2 | `GET /configs/{mappingId}` 返回 `items[]` | §6.2 行、§6.3 查询示例 | §5.1 步骤 2、§5.4 步骤 1、§9 | 按 key 展示当前值；敏感项默认脱敏；TEXT 整文件 | 一致 |
| 3 | 敏感值按 `itemKey` 查看 | §6.2 行、§6.3 请求/响应示例 | §5.4 步骤 2–5、§9 | body 携带 `itemKey` + `reason`；只返回该项；30 秒脱敏；不缓存 | 一致 |
| 4 | `POST /changes` 以 `items[]` 为主契约 | §6.2 行、§6.3 示例与规则、§7.1 步骤 1 | §5.1 步骤 2/3/5、§9 | `items[]` / `content` 互斥；UPDATE 用 items；TEXT 整文件；解析失败阻断 | 一致 |
| 5 | 批量逐项值与 `PUT /batches/{batchId}/items/{itemId}` | §6.2 行、§6.3 批量示例与规则 | §5.7 步骤 1–3、§9 | 每 DataId 一个子项；逐项填值；草稿可编辑；提交后只读 | 一致 |

错误码文案一致性：

| 错误码 | 详细设计语义 | 前端文案（原前端设计） | 结论 |
|---|---|---|---|
| `CR-415-001` | DataId 后缀无法识别或格式不支持，只读 | 该 DataId 格式不支持，只能只读浏览 | 一致 |
| `CR-422-001` | 内容解析失败，附行号/列号 | 配置内容解析失败（第 N 行），行内高亮 + 阻断提交 | 一致 |
| `CR-409-001` | 同配置已有进行中变更单 | 该配置已有进行中变更单，顶部提示 + 阻断提交 | 一致 |
| `BAT-413-001` | 批量超过上限 | 批量数量超过上限，弹窗内联错误 | 一致 |

端点总数核对：详细设计 §6.2 为 48 行；原前端设计 §9、§10 均为 48 个端点，一致。

### 4.1 页面编号对照（P-01～P-17）

> P-xx 为联调清单沿用的页面编号，非现行导航结构。现行页面与路由以详细设计 §3.5.2 为准（11 个导航页 + 登录页）；P-17 未使用，编号保留。

| 编号 | 原页面 | 现行页面（§3.5.2） | 路由 |
|---|---|---|---|
| P-01 | 登录页 | 登录页（非导航页） | —（入口页，鉴权 API `POST /auth/login`） |
| P-02 | 工作台 | 工作台 | `/workbench` |
| P-03 | 配置目录 | 配置目录 | `/catalog` |
| P-04 | 变更单列表 | 变更单 | `/changes` |
| P-05 | 新建变更 | 变更单 | `/changes` |
| P-06 | 变更详情 | 变更单 | `/changes/:changeNo` |
| P-07 | 审批 | 审批中心 | `/approvals` |
| P-08 | 发布控制台 | 发布控制台 | `/releases` |
| P-09 | 跨环境同步 | 跨环境同步 | `/env-sync` |
| P-10 | 定时任务 | 定时任务 | `/schedules` |
| P-11 | 批量变更 | 批量变更 | `/batches` |
| P-12 | 回收站 | 回收站 | `/recycle-bin` |
| P-13 | 审计与直连检测 | 审计与检测 | `/audit` |
| P-14 | 账号与授权 | 管理与设置 | `/admin` |
| P-15 | 实例与环境 | 管理与设置 | `/admin` |
| P-16 | 通知绑定 | 管理与设置 | `/admin` |
| P-17 | —（未使用，编号保留） | — | — |

## 5 差异与待确认

以下三点不影响上述 5 个缺口的一致性结论，但联调前需要对齐口径：

1. **TEXT 可编辑性表述**
   - 详细设计 §4.5：TEXT“仅全文编辑”，原前端设计 §5.1 步骤 3 为“整文件编辑”。
   - 详细设计 §5.2 迁移回填句写“其余 → TEXT（TEXT 默认只读）”。
   - 建议统一为：可识别后缀的 TEXT 允许整文件编辑；无法识别后缀的映射默认只读，发起变更返回 `CR-415-001`。联调前由后端确认最终口径。

2. **浏览态解析失败的前端表现**
   - 详细设计 §6.3 明确 `GET /configs/{mappingId}` 解析失败仍返回 200，附 `parseError`，前端只读展示、不阻断浏览。
   - 原前端设计 §6.2 只描述了提交态的 `CR-422-001`（行内高亮 + 阻断提交）。
   - 两者不冲突，但前端实现需补“浏览态只读展示 + 标注行号”的处理，避免把 `parseError` 当成页面级错误。

3. **`/mappings` 四个 ADMIN 端点的页面归属**
   - 页面编号 P-01～P-17（对照见 §4.1）未单列“配置纳管”页面，原前端设计 §10 声明“48 个端点均有页面归属”。
   - 建议 P0 明确纳管入口：归入 P-15 实例与环境，或新增 P-18 配置纳管；联调阶段可先由 ADMIN 账号直接调用接口验证。

## 6 可执行联调测试清单

> 前置数据与账号见 §7。所有请求前缀 `$BASE_URL/api/v1`；成功用例断言 2xx，异常用例必须同时匹配 HTTP 状态与错误码。

### 6.1 执行约定

**变量**

| 变量 | 来源 |
|---|---|
| `BASE_URL` | 联调环境地址，如 `https://ncg-dev.example.com` |
| `SESSION` | `POST /auth/login` 成功后的 `NCG_SESSION` Cookie |
| `XSRF_TOKEN` | 登录响应或 `XSRF-TOKEN` Cookie |
| `MAPPING_PROPERTIES` | `.properties` 的 mappingId |
| `MAPPING_YAML` | `.yml` 的 mappingId |
| `MAPPING_TEXT` | `.txt` 的 mappingId |
| `MAPPING_BAD` | 内容非法的 `.yml` 的 mappingId |
| `MAPPING_UNKNOWN` | 未知后缀（如 `.ini`）的 mappingId |
| `CHANGE_NO` / `BATCH_ID` / `ITEM_ID` / `RELEASE_ID` / `RECYCLE_ID` | 各用例创建后从响应读取 |

**通用请求头**

| 请求类型 | 请求头 |
|---|---|
| 读请求 | `Cookie: NCG_SESSION=$SESSION`、`X-Trace-Id: <用例号>-<时间戳>` |
| 写请求 | 读请求头 + `Content-Type: application/json` + `X-XSRF-TOKEN: $XSRF_TOKEN` |

**请求模板**

读请求：

```bash
curl -sS -i "$BASE_URL/api/v1/configs/$MAPPING_PROPERTIES" \
  -H "Cookie: NCG_SESSION=$SESSION" \
  -H "X-Trace-Id: TC-01-$(date +%s)"
```

写请求：

```bash
curl -sS -i -X POST "$BASE_URL/api/v1/changes" \
  -H "Content-Type: application/json" \
  -H "Cookie: NCG_SESSION=$SESSION" \
  -H "X-XSRF-TOKEN: $XSRF_TOKEN" \
  -H "X-Trace-Id: TC-09-$(date +%s)" \
  -d @tc-09.json
```

**断言规则**

1. 状态码与 `code` 必须同时匹配；只匹配其一不算通过。
2. 错误体必须含 `code`、`message`、`traceId`、`timestamp`；`traceId` 与请求头一致或由服务端回写。
3. 成功响应必须含“关键断言”列中的字段；字段缺失按失败处理。
4. 敏感明文不得出现在测试日志、截图、浏览器缓存与导出文件中。
5. 每个用例保留请求、响应与 `traceId` 作为证据。

### 6.2 正向用例（9 条）

| ID | 级别 | 端点（方法 + 路径） | 场景 | 前置 / 数据 | 请求要点 | 预期 HTTP | 预期错误码 | 关键断言 |
|---|---|---|---|---|---|---|---|---|
| TC-01 | P0 | `GET /configs/{mappingId}` | properties 读取与脱敏 | `MAPPING_PROPERTIES`；含敏感 key | 读请求，无 body | 200 | — | `format=PROPERTIES`；`items[]` 含 key；敏感值为 `••••••`；`md5`、`collectedAt` 非空 |
| TC-02 | P0 | `GET /configs/{mappingId}` | yml 识别为 YAML | `MAPPING_YAML` | 读请求 | 200 | — | `format=YAML`；嵌套 key 为点路径；`items[]` 非空 |
| TC-03 | P0 | `GET /configs/{mappingId}` | TEXT 读取 | `MAPPING_TEXT` | 读请求 | 200 | — | 返回 `content`；不返回 `items` |
| TC-04 | P1 | `GET /configs/{mappingId}` | 解析失败浏览 | `MAPPING_BAD` | 读请求 | 200 | — | `items=[]`；`parseError` 含 `line`/`column`/`message`；浏览不阻断 |
| TC-05 | P0 | `POST /configs/{mappingId}/sensitive-view` | 敏感值按项查看 | 有 `VIEW_SENSITIVE`；敏感 `itemKey` | body：`itemKey` + `reason` ≥ 10 字 | 200 | — | 只返回该项 `value`；`expiresAt` 约 30 秒后；审计可查 |
| TC-09 | P0 | `POST /changes` | 结构化变更成功 | 有 `EDIT`；`MAPPING_YAML`；`baselineMd5` | body 只传 `items[]` | 200/201 | — | `status=DRAFT`；`format=YAML`；`itemCount` 正确；`changeNo` 非空 |
| TC-13 | P0 | `POST /batches` | 批量成功 | 有 `EDIT`；多个 DataId 无活跃单 | 每个 DataId 一个 `items[]` | 200/201 | — | `batchId` 非空；`childCount` 等于 DataId 数；`status=DRAFT` |
| TC-16 | P0 | `PUT /batches/{batchId}/items/{itemId}` | 草稿编辑子项值 | DRAFT 父单；`itemId`；当前 `version` | body：`version` + `items[]` | 200 | — | `version` 递增；`itemCount` 正确；Diff 重算 |
| TC-21 | P1 | `POST /changes/{changeNo}/release` | 发布幂等重放 | 已成功发布的 `CHANGE_NO`；同一 `release_key` | 重复原发布请求 | 200 | `REL-409-002`（幂等命中） | 返回既有 `releaseId`；不新增发布记录；不重复写 TongNCS |

### 6.3 异常与边界用例（13 条）

| ID | 级别 | 端点（方法 + 路径） | 场景 | 前置 / 数据 | 请求要点 | 预期 HTTP | 预期错误码 | 关键断言 |
|---|---|---|---|---|---|---|---|---|
| TC-06 | P0 | `POST /configs/{mappingId}/sensitive-view` | 原因不足 | 有 `VIEW_SENSITIVE` | `reason` = “排查”（< 10 字） | 400 | `CR-400-001` | 不返回 `value`；不写审计明文 |
| TC-07 | P0 | `POST /configs/{mappingId}/sensitive-view` | 无敏感值权限 | 账号无 `VIEW_SENSITIVE` | 合法 `itemKey` + `reason` | 403 | `AUTHZ-403-001` | 不返回 `value` |
| TC-08 | P0 | `POST /changes` | `items` / `content` 互斥 | 有 `EDIT`；`MAPPING_YAML` | body 同时含 `items` 与 `content` | 400 | `CR-400-001` | 不创建草稿 |
| TC-10 | P0 | `POST /changes` | 格式不支持 | 有 `EDIT`；`MAPPING_UNKNOWN` | 正常 `items` body | 415 | `CR-415-001` | 不创建草稿；返回只读提示 |
| TC-11 | P1 | `POST /changes`（`PUT /changes/{changeNo}` 同断言） | 内容解析失败 | 有 `EDIT`；`MAPPING_BAD` 或非法 `content` | body 触发解析 | 422 | `CR-422-001` | `details` 含 `line`/`column`；草稿不丢 |
| TC-12 | P0 | `POST /changes` | 同配置进行中单 | 该 `mappingId` 已有 `PENDING_APPROVAL` 单 | 正常 `items` body | 409 | `CR-409-001` | 不创建第二张单；`details` 返回已有 `changeNo` |
| TC-14 | P1 | `POST /batches` | 批量未填值 | 有 `EDIT` | 子项 `items` 为空 | 400 | `CR-400-001` | 不创建父单/子项 |
| TC-15 | P1 | `POST /batches` | 批量超限 | 超过 50 个 DataId | 51 个 DataId 子项 | 413 | `BAT-413-001` | 不创建父单；提示拆分 |
| TC-17 | P1 | `PUT /batches/{batchId}/items/{itemId}` | 提交后编辑 | 子项已提交 | body：`version` + `items[]` | 409 | `SYS-409-001` | 子项内容不变；不重算 Diff |
| TC-18 | P1 | `POST /env-sync/compare`（`/apply` 同断言） | 跨环境格式不一致 | 源/目标 `format` 不同 | 正常对比/同步 body | 409 | `SYN-409-002` | 不生成目标变更单 |
| TC-19 | P0 | `POST /changes/{changeNo}/submit` | 提交基线漂移 | 已有 DRAFT 单；建单后改 TongNCS | 提交 | 412 | `CR-412-001` | 状态不前进；详情/三方 Diff 可见基线与当前 MD5 |
| TC-20 | P0 | `POST /changes/{changeNo}/release` | 发布结果不确定 | 已审批单；适配器 mock 超时/读回失败 | 正常发布 | 502 | `REL-502-001` | 发布记录 `outcome=INDETERMINATE`；不自动重试；提供对账入口 |
| TC-22 | P1 | `POST /recycle-bin/{id}/restore` | 恢复同名冲突 | 回收站项与现存配置同名 | 正常恢复 | 409 | `RB-409-001` | 不覆盖；只返回“覆盖并重新审批/放弃” |

### 6.4 执行顺序与依赖

1. 认证：`POST /auth/login` 获取 `SESSION` 与 `XSRF_TOKEN`；`GET /auth/me` 校验权限点。
2. 读取：TC-01～TC-04 使用不同 `mappingId`；TC-05～TC-07 使用有/无 `VIEW_SENSITIVE` 的两个账号。
3. 建单校验：先跑 TC-08、TC-10、TC-11、TC-12 异常路径；TC-09 使用新的 `mappingId`，避免与 TC-12 的活跃单冲突。
4. 批量：TC-13 创建父单 → TC-16 编辑子项 → 提交父单 → TC-17；TC-14、TC-15 使用独立请求，不依赖 TC-13。
5. 同步：TC-18 使用源/目标 `format` 不同的映射对。
6. 发布：TC-19 使用独立 DRAFT 单；TC-20 需切换适配器 mock 返回超时；TC-21 在一次成功发布后重复同一请求。
7. 恢复：TC-22 使用同名回收站项。

> 同一 `mappingId` 同时只允许 1 张进行中变更单；用例间通过独立映射或取消/完成前置单隔离状态。

### 6.5 错误码覆盖矩阵

| 错误码 | HTTP | 覆盖用例 | 说明 |
|---|---|---|---|
| `CR-400-001` | 400 | TC-06、TC-08、TC-14 | 参数缺失或格式错误 |
| `AUTHZ-403-001` | 403 | TC-07 | 缺少权限点 |
| `CR-415-001` | 415 | TC-10 | 后缀无法识别或格式不支持 |
| `CR-422-001` | 422 | TC-11 | 内容解析失败 |
| `CR-409-001` | 409 | TC-12 | 同配置已有进行中变更单 |
| `BAT-413-001` | 413 | TC-15 | 批量超过上限 |
| `SYS-409-001` | 409 | TC-17 | 乐观锁冲突或已提交 |
| `SYN-409-002` | 409 | TC-18 | 跨环境映射或格式不一致 |
| `CR-412-001` | 412 | TC-19 | 提交前基线漂移 |
| `REL-502-001` | 502 | TC-20 | 写入结果不确定 |
| `REL-409-002` | 200 | TC-21 | 幂等命中（非失败） |
| `RB-409-001` | 409 | TC-22 | 恢复同名冲突 |

### 6.6 通过标准

- P0 用例全部通过；P1 未通过项必须记录 `traceId`、请求、响应与阻塞原因。
- 不允许出现未预期的 5xx；`SYS-500-001` 视为缺陷。
- 每个错误响应的 `code`、`message`、`traceId`、`timestamp` 完整。
- 5 个缺口的契约字段均被覆盖：`format`、`items[]`、`itemKey`、`items`/`content` 互斥、批量 `version`；`parseError`、`sensitive`、`expiresAt` 作为断言字段一并校验。
- 敏感明文零泄露：日志、截图、导出与浏览器缓存中不得出现。

## 7 联调准备

1. **种子数据**：1 个应用、3 个环境（dev / staging / prod）、4 个 DataId（`.properties`、`.yml`、`.json`、`.txt`），其中 `.properties` 含至少 1 个敏感 key。
2. **账号**：管理员、配置编辑者、审批人（应用负责人）、发布人、审计员各 1 个；准备 1 个无 `VIEW_SENSITIVE` 的编辑者用于权限反例。
3. **调用顺序**：`/auth/login` → `/auth/me` → `/apps` → `/apps/{appId}/configs` → `/configs/{mappingId}` → `POST /changes` → `submit` → `approve` → `release` → `GET /releases` → `GET /audit-events`。
4. **Mock 约定**：`items[]`、`format`、`parseError`、`sensitive`、`expiresAt` 必须按本清单返回；错误体必须包含 `code`、`message`、`traceId`、`timestamp`。
5. **验收口径**：48 个端点全部可达；5 个缺口示例与前端页面字段一一对应；22 条可执行用例按 §6.6 通过标准执行。

## 8 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-22 | 首版：按详细设计 v1.4 生成 48 个端点清单；补齐 5 个缺口接口示例、详细设计 vs 前端设计核对表与联调用例 |
| v1.1 | 2026-11-22 | 将 22 条联调用例升级为可执行测试清单：新增端点、预期 HTTP、预期错误码、前置数据、请求要点与关键断言列；补充执行约定、执行顺序、错误码覆盖矩阵与通过标准 |
| v1.2 | 2026-11-22 | 前端设计文档收口：原《前端交互设计》v1.1 已并入详细设计 §3.5，依据与引用改为现行文档并标注“原”章节号；新增 §4.1 页面编号对照（P-01～P-17 → 现行 11 页 + 登录页） |

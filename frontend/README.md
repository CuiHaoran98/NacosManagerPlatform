# NCG 配置治理平台 · 前端

Vue 3 + Vite + TypeScript + Element Plus（按需引入、Composition API）+ Pinia + Vue Router。
对接后端 `http://127.0.0.1:8085/api/v1`（dev 代理，见 `vite.config.ts`）。

## 快速开始

```bash
npm install        # 注意：默认 npm 缓存目录只读时加 --cache /tmp/ncg-npm-cache
npm run dev        # 开发（/api 代理到 127.0.0.1:8085）
npm run build      # vue-tsc --noEmit + vite build（必须通过后才交付）
npm run typecheck  # 仅类型检查
```

## 目录结构与分工约定（必读）

```
src/
├── api/            # API 层：48 端点全量预注册 【共享层，禁止重构】
│   ├── http.ts     # axios 封装、X-Trace-Id/X-XSRF-TOKEN、ApiError、全局错误提示、401 统一处理
│   ├── types.ts    # 全量 DTO（与 doc/ncg-api-contract.md §3 对应）
│   └── modules/    # 按域拆分的端点函数（auth/catalog/change/approval/release/…）
├── router/         # 12 条业务路由 + 登录页 + 入口守卫 【共享层，禁止重构】
│   ├── index.ts
│   └── nav.ts      # 侧边导航分组（治理/发布/监督/管理）
├── stores/         # Pinia：auth（会话）、app（环境/实例状态）【共享层，禁止重构】
├── components/     # 共享组件 【共享层，禁止重构】
├── layouts/        # AppLayout（侧边导航 + 顶栏）【共享层，禁止重构】
├── styles/         # 设计 token（tokens.css）+ 基础样式（base.css）【共享层，禁止重构】
└── views/          # 各业务模块视图【各模块所有者在此实现，一人一目录】
    ├── login/      workbench/  catalog/  changes/  approvals/  schedules/
    ├── batches/    releases/   env-sync/ recycle-bin/  audit/  admin/
```

**分工规则：**

1. **视图代码归各模块 `views/<模块>/` 目录**。每个页面的组件、子组件、组合式函数写在自己的模块目录内，不跨模块引用对方 views/ 内部文件。
2. **路由与 API 层已预注册**：12 条路由指向 `views/` 下的页面壳；48 个端点函数在 `src/api/modules/` 全部就位。新增页面功能时只改自己的 view 文件，**不要**改 `src/router/`、`src/api/`、`src/components/`、`src/styles/`、`src/stores/`、`src/layouts/`（共享层）。确需扩展共享层，向架构负责人申请。
3. **共享组件直接用**（`import X from '@/components/X.vue'`）：PageShell（页面壳）、TableToolbar（表格工具条）、DiffView（差异展示）、SensitiveValue（敏感值脱敏展示）、EmptyState（空态：原因+下一步）、ErrorState（错误态：错误码+TraceId+建议）、SkeletonBlock（加载骨架）。
4. **设计 token 走 CSS 变量**（`styles/tokens.css`）：颜色一律用 `--ncg-*`，禁止硬编码色值；间距用 8px 倍数（`--ncg-space-*`）；字号用 `--ncg-fs-*`（1.25 阶梯 12/13/14/16/20/25/31）、圆角用 `--ncg-radius-*`；动效用 `--ncg-ease` / `--ncg-dur-*`；正文行宽 `--ncg-measure`（65ch）、宽容器 `--ncg-content-max`（1280px）。`--ncg-text-muted/--ncg-border/--ncg-accent/--ncg-success/--ncg-warning/--ncg-danger` 是兼容别名，新代码请直接用原语 token（text-3/line/volt/amber/red）。

## 设计规范（frontend-design-pro）

- **色板**：OKLCH；深色基调（ink `oklch(17.2% 0.008 264)` ≈ #0f0f0f 亮度、panel `oklch(21.2% 0.015 266)`）；强调 volt `oklch(90% 0.191 123.3)`；状态 amber/red/cyan；中性色带色调（hue ≈264），禁纯黑纯灰。
- **字体**：显示字体 **DM Sans + Noto Sans SC**（`--ncg-font-display`），数据/代码 **IBM Plex Mono**（`--ncg-font-data`，tabular-nums）。**禁止** Arial / Inter / system-ui 作显示字体。
- **间距**：8px 倍数（8/16/24/32/40/48/64）；正文行长 65ch、宽容器 1280px；区块之间留白 ≥24px（呼吸感，禁止贴边拥挤）。
- **动效**：缓动 `cubic-bezier(0.16, 1, 0.3, 1)`；微交互 100–200ms；禁 bounce；单次动画 ≤600ms；尊重 `prefers-reduced-motion`（tokens.css 已全局降级）。
- **可访问性**：焦点可见（`:focus-visible` outline volt）、图标按钮必须有 aria-label、空态/错误态文案说人话。

## API 层使用约定

```ts
import { changeApi } from '@/api'           // 或 import { listChanges } from '@/api/modules/change'
import type { ApiError } from '@/api/http'

try {
  const page = await changeApi.listChanges({ page: 0, size: 20 })
} catch (e) {
  const err = e as ApiError                  // code / message / traceId / details / timestamp
  // 按 err.code 分支处理，不要解析 message 文案
}
```

- Base URL `/api/v1`（axios 实例已配置）；写请求自动带 `X-XSRF-TOKEN`（读 Cookie `XSRF-TOKEN`），每个请求自动带 `X-Trace-Id`。
- **错误体**统一 `{code, message, traceId, details?, timestamp}`；全局错误提示已内置（`silent: true` 可关闭单次）；401 统一跳登录并保留 redirect（登录接口自身的 401 除外）。
- **分页**：`page` 从 0 开始，`size` 默认 20、最大 100；响应 `{items, total, page, size}`。
- **时间**：后端 ISO-8601 UTC；展示用 `Asia/Shanghai`。
- **敏感值**：一律用 `<SensitiveValue :mapping-id :item-key />`，禁止明文直接渲染；查看明文需原因 ≥10 字并自动 30 秒再脱敏。
- **状态**：只使用后端返回的状态值，前端不推导业务状态。

## 共享组件速查

| 组件 | 用途 | 关键 props |
|---|---|---|
| `PageShell` | 页面壳（标题/描述/操作区/加载/错误） | `title` `description` `loading` `error` |
| `TableToolbar` | 列表页工具条 | `searchValue` `loading`；slots: `filters` `actions` |
| `DiffView` | 配置差异（pair 双列 / three 三列基线漂移） | `rows: DiffRow[]` `mode` `sensitiveKeys` |
| `SensitiveValue` | 敏感值脱敏 + 审批式查看 | `mappingId` `itemKey` |
| `EmptyState` | 空态（原因 + 下一步） | `reason` `nextStep` `actionText` |
| `ErrorState` | 错误态（错误码 + TraceId + 建议） | `code` `message` `traceId` |
| `SkeletonBlock` | 加载骨架（保持布局稳定） | `rows` `type` |

## 已注册路由（12 + 登录）

| 分组 | 路由 | 页面 |
|---|---|---|
| 治理 | `/workbench` `/catalog` `/changes` `/changes/:changeNo` `/approvals` `/schedules` `/batches` | 工作台/配置目录/变更单/变更详情/审批中心/定时任务/批量变更 |
| 发布 | `/releases` `/env-sync` `/recycle-bin` | 发布控制台/跨环境同步/回收站 |
| 监督 | `/audit` | 审计与检测 |
| 管理 | `/admin` | 管理与设置 |
| 公共 | `/login` | 登录（入口守卫：未登录 → `/auth/me` 校验失败 → 跳登录并保留 redirect） |

## 业务红线（rule.md 摘录，实现时遵守）

- 写操作三段式：预检 → 执行 → 写后读回（MD5）；发布结果三态 `verified / rejected / indeterminate`，**不确定结果只进对账、不重试**。
- 唯一写入口 Release；变更状态与审计同事务；冻结基线，出现漂移必须显式确认。
- 敏感值全程脱敏；查看需原因并留审计。
- 配置格式按后缀固化（`.properties` / `.yml` / `.yaml` / `.json`，其余 TEXT）；解析失败给出行号并阻断。
- 未知参数标 `[TBD]`，不臆造。

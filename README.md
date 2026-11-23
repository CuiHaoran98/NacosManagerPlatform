# NacosManagerPlatform（NCG）— Nacos 配置治理平台

> 治理层：变更单 / 审批 / 发布编排 / 回滚 / 审计 / 直连检测 / 影响面 / 通知 / 环境同步 / 批量 / 定时任务 / 回收站。
> 配置内容的唯一事实源是底层配置中心（代码对接真实 **Nacos** OpenAPI，内置 `fake` 模式可离线演示；文档历史称 TongNCS，映射见 `V1__init.sql` 头注释与 `NacosConfigPort` javadoc）。

**核心能力**：在 Nacos 之上建立「先审批、后发布、全留痕」的配置变更治理闭环——

- ✅ **变更单全生命周期**：创建 → 提交 → 审批（他人审批、fail-closed）→ 发布 → 读回校验（`expectedMd5 == readbackMd5`）→ VERIFIED
- ✅ **唯一写入口**：对 Nacos 的写操作只从 `port.release` 两处发出，杜绝旁路直改；环境实例 UNAVAILABLE 时自动 `SYS-423-001` 只读降级
- ✅ **审计链**：append-only 触发器 + Hash 链（`HashChain`），同事务落审计，`GET /api/v1/audit-events/chain` 可校验 `ok:true`
- ✅ **安全红线**：indeterminate 禁自动重试、权限 fail-closed、敏感列 AES-256-GCM 加密、XSRF 双提交 Cookie、审计归档
- ✅ **双进程职责分离**：Web（REST/会话）+ Worker（调度/发件箱/直连扫描/批量），单活租约 + 任务幂等，热备接管
- ✅ **双模式适配**：`fake`（内存模拟 Nacos，离线演示/测试）/ `real`（真实 Nacos OpenAPI），同一 `NacosConfigPort` 抽象

---

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.3.5 · Spring Security · Spring Session JDBC · Spring Data JDBC |
| 数据库 | PostgreSQL 15/16（schema `ncg`，23 表，Flyway `V1__init.sql` + `V2__contract_alignment.sql`） |
| 加密 | BouncyCastle 1.78（AES-256-GCM，密钥轮换 `NCG_CRYPTO_KEY_ID`） |
| 前端 | Vue 3.5 · TypeScript 5.8 · Vite 6 · Element Plus 2.9 · Pinia 3 · Vue Router 4 |
| 测试 | JUnit 5 · Testcontainers（`postgres:16-alpine`）· 53 用例（40 实跑 + 13 Docker 门控） |
| 构建 | Maven 3.9（双可执行 jar）· Vite（`frontend/dist` 静态产物） |

## 系统架构

```
┌──────────────────────────────┐
│  frontend/（Vue 3 + Element Plus）│
│  12 页面：登录/工作台/变更/审批/   │
│  发布/目录/审计/批量/定时/同步/     │
│  回收站/管理                      │
└──────────────┬───────────────┘
               │ REST（48 端点，XSRF 双提交）
┌──────────────▼───────────────┐
│  ncg-…-web.jar（REST/会话）       │
│  21 控制器 · 81 映射 · 唯一写入口   │
├──────────────────────────────┤
│  领域模块：change · approval ·    │
│  release · audit · batch ·       │
│  scheduler · envsync · recycle · │
│  catalog · auth · notification   │
└──────────────┬───────────────┘
               │ NacosConfigPort（fake / real）
┌──────────────▼───────────────┐
│  Nacos 配置中心（唯一事实源）       │
└──────────────────────────────┘

┌──────────────────────────────┐
│  ncg-…-worker.jar（调度/发件箱）  │
│  定时发布·批量·直连扫描·通知派发·   │
│  回收站清理·审计链校验·实例探活     │
│  单活租约 ncg_worker_lease       │
└──────────────────────────────┘
        元数据 ↕ PostgreSQL 15+（Flyway 23 表）
```

## 快速开始

### 1. 构建

```bash
# JDK 21 + Maven 3.9（仓库内自带工具链可改用 .tools/jdk21、.tools/maven）
export JAVA_HOME=$PWD/.tools/jdk21
export PATH=$JAVA_HOME/bin:$PWD/.tools/maven/bin:$PATH

mvn -B -ntp -Dmaven.repo.local=.tools/m2 package -DskipTests
```

产出双可执行包：

- `target/ncg-0.1.0-SNAPSHOT-web.jar` — Web（REST/会话）
- `target/ncg-0.1.0-SNAPSHOT-worker.jar` — Worker（调度/发件箱/直连扫描/批量）

> ⚠️ `spring-boot-starter-parent` 继承了 `id=repackage` 的默认执行，pom 里自定义执行必须也叫 `repackage`（覆盖它），否则报 `Unable to find a single main class`。

### 2. 数据库

PostgreSQL 15+（设计基线 16，15 实测兼容）。Flyway 启动时自动建 schema `ncg`（23 表 + 审计 append-only 触发器）。

```bash
/usr/lib/postgresql/15/bin/postgres -D .tools/pgdata -p 55432 -k /tmp -c listen_addresses=127.0.0.1
```

> 改动 `V1__init.sql` 后 checksum 变化，必须重建库：`DROP DATABASE ncg; CREATE DATABASE ncg OWNER ncg;`

### 3. 运行

```bash
# Web
java -jar target/ncg-0.1.0-SNAPSHOT-web.jar --server.port=8085 --spring.profiles.active=local

# Worker（完整能力需要 web + worker 双进程）
java -jar target/ncg-0.1.0-SNAPSHOT-worker.jar --spring.profiles.active=local
```

| 环境变量 | 说明 | 示例 |
|---|---|---|
| `NCG_DB_URL` / `NCG_DB_USER` / `NCG_DB_PASSWORD` | 元数据库 | `jdbc:postgresql://127.0.0.1:55432/ncg` |
| `NCG_CRYPTO_KEY` | AES-256-GCM 密钥（base64 32 字节）；未配置则敏感列写入 fail-closed | — |
| `NCG_CRYPTO_KEY_ID` | 密钥标识（轮换用） | `test-key` |
| `NCG_BOOTSTRAP_ADMIN_PASSWORD` | 首个管理员密码（仅空库生效） | ≥12 位 |
| `NCG_COOKIE_SECURE` | **代码默认 `true`**，http 本地部署必须显式设 `false`，否则登录 200 后全部 401 | 本地 `false`（必设） |
| `NCG_NACOS_MODE` | `fake`（默认）/ `real` | local profile 强制 fake |
| `NCG_NACOS_<ENV>_ENDPOINT` / `_TOKEN` | 真实 Nacos 连接 | `NCG_NACOS_DEV_ENDPOINT` 等 |

Worker 要点：`server.port=-1`、管理端口 9081；7 个调度任务由 `ncg.worker.enabled` 控制（web 默认 `false` 不跑调度）；单活靠 `ncg_worker_lease`（TTL 30s）+ 任务幂等，热备经租约过期接管。

### 4. 前端

```bash
cd frontend
npm install
npm run dev       # 开发
npm run build     # 类型检查 + 构建（frontend/dist）
```

前后端联调契约：`doc/ncg-api-contract.md`（48 端点、22 条可执行用例）；联调报告：`frontend/INTEGRATION-REPORT.md`（23/23 通过）。

## 测试

```bash
mvn -B -ntp -Dmaven.repo.local=.tools/m2 test
```

- **53 用例**：40 实跑 + 13 Docker 门控跳过（`@Testcontainers(disabledWithoutDocker=true)`）
- 集成测试（`integration/`）需 Docker 拉取 `postgres:16-alpine` 真库实跑；核心链：建单→审批→发布→读回 VERIFIED→审计链 `ok:true`，失败路径（对账/回滚双模式/软删恢复/只读降级）全覆盖

## 冒烟验证链（HTTP 级）

```bash
# 登录（表单编码，不是 JSON）
curl -c cookies.txt -X POST http://127.0.0.1:8085/api/v1/auth/login \
  --data-urlencode "username=ncg-admin" --data-urlencode "password=..."
X=$(grep XSRF-TOKEN cookies.txt | awk '{print $7}')   # 写操作带 X-XSRF-TOKEN 头
```

建单 `POST /api/v1/changes` → 提交 `POST /changes/{no}/submit` → 审批（他人）`POST /changes/{no}/approval` → 发布 `POST /changes/{no}/release` → 校验 `GET /api/v1/releases?changeNo=`（`VERIFIED` 且 `expectedMd5==readbackMd5`）→ 审计链 `GET /api/v1/audit-events/chain?date=YYYY-MM-DD`（`ok:true`）。

约束速记：同一映射同时只允许 1 个活跃变更单；重复发布同 `releaseKey` 返回既有记录（HTTP 200）；环境实例 UNAVAILABLE 时写接口 `SYS-423-001` 只读降级。

## 目录结构

```
NacosManagePlatform/
├── pom.xml                      # Maven（双 jar：web / worker）
├── rule.md                      # 行为红线 v1.4（26 条）
├── agent.md                     # 项目上下文 v1.5
├── src/main/java/com/nacos/ncg/
│   ├── web/                     # 21 控制器 + 安全配置 + 异常处理
│   ├── change/  approval/  release/   # 变更 / 审批 / 发布（app·domain·repo 三层）
│   ├── audit/   batch/    scheduler/  # 审计 / 批量 / 定时
│   ├── envsync/ catalog/  auth/       # 环境同步 / 目录 / 认证授权
│   ├── adapter/nacos/           # NacosConfigPort（fake / real 双实现）
│   └── shared/                  # HashChain · RiskCalculator · PermissionPolicy · JSON
├── src/main/resources/
│   ├── application*.yml         # local / web / worker profile
│   └── db/migration/            # Flyway V1__init.sql · V2__contract_alignment.sql
├── src/test/java/               # 10 测试类（53 用例）
├── frontend/                    # Vue 3 + Element Plus 前端（12 页面）
└── doc/                         # PRD · 架构 · 详细设计 · API 契约 · 验收报告
```

## 文档索引

| 文件 | 内容 |
|---|---|
| [`doc/ncg-deployment-guide.md`](doc/ncg-deployment-guide.md) | **部署与使用说明（前端+后端，入口）** |
| [`doc/nacos-config-governance-prd.html`](doc/nacos-config-governance-prd.html) | PRD v1.1 |
| [`doc/tongncs-config-governance-architecture.html`](doc/tongncs-config-governance-architecture.html) | 系统架构 |
| [`doc/tongncs-config-governance-detailed-design.md`](doc/tongncs-config-governance-detailed-design.md) | 技术详细设计 v1.4（含 §3.5 前端交互、§4.5 格式契约、§6.2 接口清单 48 端点） |
| [`doc/ncg-api-contract.md`](doc/ncg-api-contract.md) | 前后端联调接口清单 v1.2（48 端点、22 条可执行用例） |
| [`doc/ncg-user-guide.html`](doc/ncg-user-guide.html) | 平台使用说明（15 章） |
| [`doc/acceptance-report.md`](doc/acceptance-report.md) | 验收报告（AC-01~26：26/26 PASS） |
| [`doc/ncg-frontend-acceptance.md`](doc/ncg-frontend-acceptance.md) | 前端独立验收（15 章走查、反模式六轴） |
| [`doc/implementation-traceability.md`](doc/implementation-traceability.md) | FR→规则→约束→AC→代码追踪矩阵 |
| [`doc/static-acceptance-report.md`](doc/static-acceptance-report.md) | 静态验收 G-01~16 |
| [`frontend/README.md`](frontend/README.md) / [`frontend/INTEGRATION-REPORT.md`](frontend/INTEGRATION-REPORT.md) | 前端工程约定 / 前后端联调报告（23/23、D1-D14） |
| [`rule.md`](rule.md) / [`agent.md`](agent.md) | 行为红线 26 条 / 项目上下文 |

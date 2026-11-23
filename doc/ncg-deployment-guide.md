# NCG 平台部署与使用说明

> 适用版本：后端 `0.1.0-SNAPSHOT`（web/worker 双 jar）+ 前端 `frontend/`（Vue 3 + Element Plus）。
> 配套阅读：[README.md](../README.md)（后端运行手册）、[frontend/README.md](../frontend/README.md)（前端工程约定）、[doc/ncg-user-guide.html](ncg-user-guide.html)（平台使用说明，15 章）。
> 本文命令均在仓库根目录执行：`cd /vol1/1000/share/coding/NacosManagePlatform`。

## 1. 组件与拓扑

| 组件 | 端口 | 说明 |
|---|---|---|
| web（`ncg-...-web.jar`） | 8085 | REST/会话/API 全部入口（`/api/v1`） |
| worker（`ncg-...-worker.jar`） | 9081（管理） | 调度任务组：定时发布/批量/直连扫描/通知派发/回收站清理/审计链校验/实例探活 |
| PostgreSQL 15+ | 55432 | 元数据库（Flyway `V1__init.sql`，schema `ncg`，23 表） |
| 前端（Vite） | 5173（dev） | `/api` 代理到 `127.0.0.1:8085`；生产为静态 `dist/` |
| Nacos | — | 配置内容事实源；`fake`（本地模拟）/ `real`（OpenAPI）双模式 |

请求链：浏览器 → 前端（dev :5173 或静态托管）→ `/api/v1` → web :8085 → PostgreSQL + Nacos；worker 与 web 并行，**只跑 web 不执行调度**（定时发布、通知派发等需要双进程）。

## 2. 环境要求

| 依赖 | 版本 | 获取方式 |
|---|---|---|
| Java | 21（Temurin） | 随库自带：`.tools/jdk21` |
| Maven | 3.9.9 | 随库自带：`.tools/maven` |
| Node.js | ≥20（实测 24.22.1） | 系统安装 |
| PostgreSQL | 15+（实测 15） | 系统安装（`/usr/lib/postgresql/15/bin/`），数据目录 `.tools/pgdata` |
| Docker | 可选 | 仅 Testcontainers 集成测试需要 |

## 3. 快速开始（本地开发 / 演示）

### 3.1 启动数据库

```bash
/usr/lib/postgresql/15/bin/postgres -D .tools/pgdata -p 55432 -k /tmp -c listen_addresses=127.0.0.1
```

> 沙箱内守护进程会被回收，须以托管后台任务方式运行。已有数据目录 `.tools/pgdata` 可直接复用（含演示数据）。

### 3.2 构建后端（已有 `target/*.jar` 可跳过）

```bash
export JAVA_HOME=$PWD/.tools/jdk21
export PATH=$JAVA_HOME/bin:$PWD/.tools/maven/bin:$PATH
mvn -B -ntp -Dmaven.repo.local=.tools/m2 package -DskipTests
```

产出：`target/ncg-0.1.0-SNAPSHOT-web.jar`、`target/ncg-0.1.0-SNAPSHOT-worker.jar`。

### 3.3 启动 web + worker

```bash
export JAVA_HOME=$PWD/.tools/jdk21; export PATH=$JAVA_HOME/bin:$PATH
export NCG_DB_URL='jdbc:postgresql://127.0.0.1:55432/ncg?sslmode=disable'
export NCG_COOKIE_SECURE=false                     # http 演示必设！代码默认 true（见 §7 坑）
export NCG_CRYPTO_KEY=$(openssl rand -base64 32)   # 首次生成后请保存；已有数据必须沿用原密钥
export NCG_CRYPTO_KEY_ID=test-key
# 本机既有演示数据沿用测试键（不换 key）：
#   NCG_CRYPTO_KEY='MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='  KEY_ID=test-key（见 .tools/DCAFPTest.bak）

nohup java -jar target/ncg-0.1.0-SNAPSHOT-web.jar \
  --server.port=8085 --spring.profiles.active=local >/tmp/ncg-web.log 2>&1 &

nohup java -jar target/ncg-0.1.0-SNAPSHOT-worker.jar \
  --spring.profiles.active=local >/tmp/ncg-worker.log 2>&1 &
```

- `local` profile 强制 `NCG_NACOS_MODE=fake`；调度任务组由 `ncg.worker.enabled` 控制（web 默认 false）。
- 首个管理员仅在**空库**时由 `NCG_BOOTSTRAP_ADMIN_PASSWORD`（≥12 位）创建，用户名 `ncg-admin`；已有库跳过。

### 3.4 启动前端

```bash
cd frontend
npm install     # npm 缓存目录只读时：npm install --cache /tmp/ncg-npm-cache
npm run dev     # http://localhost:5173，/api 自动代理到 127.0.0.1:8085
```

浏览器打开 `http://localhost:5173` → 登录即用。

### 3.5 局域网访问（NAS 部署）

`frontend/vite.config.ts` 已设 `server.host: true`（监听 0.0.0.0）+ `strictPort: true`，`npm run dev` 启动后**局域网直接访问**：

```
http://<NAS-IP>:5173/     # 前端入口（如 http://192.168.31.79:5173/）
http://<NAS-IP>:8085/     # 纯 API 入口——根路径返回 401 JSON 是正常响应（没有 UI、未带会话）
```

- 局域网走 **http**（非 localhost），会话 Cookie 不能带 `Secure`：启动 web 必须 `NCG_COOKIE_SECURE=false`（默认 `true` 会登录 200 之后全部 401）。
- 前端 `/api` 由 Vite **服务端**代理到 `127.0.0.1:8085`，浏览器侧与页面同源，无 CORS 问题；8085 无需对局域网开放（只让 5173 可达也行）。
- 若 `http://<NAS-IP>:5173` 仍「拒绝连接」而本机 `localhost:5173` 正常：Vite 已监听 0.0.0.0 的前提下，只剩 NAS 防火墙未放行 5173 一种可能。
- 长期对外建议生产姿势（§4.2）：`npm run build` 出 `dist/`，nginx 静态托管 + 反代 `/api`。

## 4. 生产部署

### 4.1 后端（web + worker 双进程）

jar 与启动命令同 §3.2–3.3，生产环境变量按需覆盖：

| 环境变量 | 说明 | 生产建议 |
|---|---|---|
| `NCG_DB_URL` / `NCG_DB_USER` / `NCG_DB_PASSWORD` | 元数据库连接 | `jdbc:postgresql://<pg-host>:5432/ncg`（PG 未开 SSL 时加 `?sslmode=disable`） |
| `NCG_CRYPTO_KEY` / `NCG_CRYPTO_KEY_ID` | AES-256-GCM 敏感值加密（base64 32 字节） | **必配**，未配置时敏感列写入 fail-closed；密钥妥善保管、丢失即历史敏感值不可解 |
| `NCG_BOOTSTRAP_ADMIN_PASSWORD` | 首个管理员（仅空库生效） | ≥12 位 |
| `NCG_COOKIE_SECURE` | 会话 Cookie Secure 标记（**代码默认 `true`**） | HTTPS 部署才可 `true`；http 部署必须显式 `false`，否则浏览器不存会话 Cookie、严格 HTTP 客户端不回传——登录 200 之后全部 401 |
| `NCG_NACOS_MODE` | `fake` / `real` | 生产 `real` |
| `NCG_NACOS_<ENV>_ENDPOINT` / `_TOKEN` | 真实 Nacos 连接（按环境） | 如 `NCG_NACOS_DEV_ENDPOINT` |

进程守护：两个 jar 用 systemd / supervisor 托管即可；worker 单活（`ncg_worker_lease` 租约 TTL 30s），可双实例热备。

### 4.2 前端（静态构建 + 反向代理）

```bash
cd frontend
npm run build        # vue-tsc --noEmit + vite build，产物 frontend/dist/
```

nginx 示例（页面与 `/api` **同源**部署，无需 CORS）：

```nginx
server {
  listen 80;
  server_name ncg.example.com;
  root /srv/ncg/dist;

  location / {
    try_files $uri $uri/ /index.html;      # 前端路由 history 模式
  }
  location /api/ {
    proxy_pass http://127.0.0.1:8085;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

要点：Cookie 与 `XSRF-TOKEN` 原样透传（写请求依赖 `X-XSRF-TOKEN`）；API base 为相对路径 `/api/v1`，前端无运行时配置项。跨域部署需后端额外开 CORS + `withCredentials`，默认按同源设计，不建议。

## 5. 部署后验证

```bash
# ① 端口/进程
ss -ltn | grep -E '8085|9081|55432|5173'

# ② 后端存活（返回 401 即正常：守卫生效）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8085/api/v1/auth/me

# ③ 登录冒烟（表单编码，不是 JSON）
curl -c /tmp/cookies.txt -X POST http://127.0.0.1:8085/api/v1/auth/login \
  --data-urlencode "username=ncg-admin" --data-urlencode "password=<口令>"
# 写操作带 X-XSRF-TOKEN 头（值取 cookies.txt 中的 XSRF-TOKEN）

# ④ 前端冒烟
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5173/
```

- 完整回归：`.tools/tc_run.py`（22 条契约用例 + 鉴权链，基线 23/23 PASS），复跑指引见 [frontend/INTEGRATION-REPORT.md](../frontend/INTEGRATION-REPORT.md) §8–9。
- 后端测试：`mvn -B -ntp -Dmaven.repo.local=.tools/m2 test`（53 用例：40 实跑 + 13 Docker 门控跳过）。
- 冒烟业务链（HTTP 级）：建单 → 提交 → 审批（他人）→ 发布 → `GET /releases?changeNo=` 读回 `VERIFIED`（`expectedMd5==readbackMd5`）→ `GET /audit-events/chain?date=YYYY-MM-DD`（`ok:true`）。

## 6. 使用说明

完整操作手册：**[doc/ncg-user-guide.html](ncg-user-guide.html)**（浏览器直接打开，15 章：工作台/配置目录/变更/审批/发布/跨环境同步/批量/审计/回收站/管理）。

快速上手路径：

1. 登录 → **工作台**（待审批 / 最近发布 / 直连告警）；
2. **配置目录**：DataId = 配置文件，items = 文件内 key（按项展示当前值；敏感项默认脱敏，查看需原因且 30 秒自动再脱敏）；
3. **发起变更**：结构化格式（properties/YAML/JSON）按项填值，CREATE/TEXT 整文件用 content（两者互斥），提交前 Diff 预览；
4. **审批**（换账号：提交人不能审批，`AUTHZ-403-002/003`）→ **发布控制台**执行发布；
5. 发布结果三态 `verified / rejected / indeterminate`——**不确定结果只进人工对账，不自动重试**；
6. 全程审计留痕（哈希链可校验）。

角色速记：同一映射同时只允许 1 个活跃变更单；未知格式 DataId 只读禁变更（`CR-415-001`）；解析失败给出行号并阻断（`CR-422-001`）。

## 7. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 后端启动报 PG SSL 错误 | 本机 PG 未开 SSL：JDBC URL 加 `?sslmode=disable` |
| 敏感列写入 500 / fail-closed | `NCG_CRYPTO_KEY` 未配置；配置后历史数据须用原密钥才能解密 |
| 定时发布/通知不执行 | 只启动了 web；启动 worker（双进程） |
| 改了 `V1__init.sql` 起不来 | Flyway checksum 变化，必须重建库：`DROP DATABASE ncg; CREATE DATABASE ncg OWNER ncg;` |
| 登录后写操作 403 | 写请求需带 `X-XSRF-TOKEN`（取 Cookie `XSRF-TOKEN`）；登录是表单编码 |
| 登录返回 200 但随后全部 401（含 tc_run.py AUTH-CHAIN 挂） | 会话 Cookie 带 `Secure` 而部署在 http 上：`NCG_COOKIE_SECURE` **代码默认 true**，http 部署必须显式 `NCG_COOKIE_SECURE=false`（本坑已实测：Python urllib 严格拒发 Secure Cookie，curl/browsers on localhost 宽容故时灵时不灵） |
| `npm install` 卡住/权限错 | 缓存目录只读：`npm install --cache /tmp/ncg-npm-cache` |
| 端口被占用 | web 改 `--server.port`，前端改 `vite.config.ts` 的 `server.port` 并同步 `proxy.target` |
| 空 JSON `{}` 请求 | 应返回 400 `CR-400-001`（缺陷 D14 已修复）；若 500 即回归，见联调报告 §3.1 |
| 电脑访问 `http://<NAS-IP>:5173` 拒绝连接 | Vite 默认只绑回环（`::1`）；本仓库 `vite.config.ts` 已设 `server.host: true`，重启 `npm run dev` 生效；仍拒绝＝NAS 防火墙未放行 5173（见 §3.5） |
| 配置目录弹一串 `config not found: /DEFAULT_GROUP/…`（SYS-404-001） | local 演示的 fake Nacos 内容曾只存内存，重启即丢，映射行还在目录里就持续 404。已修（D16）：fake store 磁盘持久化＋预取静默＋DELETED 行过滤。预防：web 启动带 `NCG_FAKE_STORE=$PWD/.tools/fake-store.json`；改完测试数据后 `POST /api/v1/_fixture/persist` 落盘（登录后调用）；`_fixture/seed` 必须带真实 content（`GET /configs` 对结构化格式只返 items 不返 content，别拿它回灌） |
| fake Nacos 演示数据重启后丢失/变空 | 同上：`NCG_FAKE_STORE` 未设置时 fake store 退回纯内存模式（单测默认）。持久化文件损坏时 stderr 告警并空启动，用 `_fixture/seed` 重灌 + `_fixture/persist` 重建 |

## 8. 文档出处

| 文档 | 内容 |
|---|---|
| [README.md](../README.md) | 后端构建/数据库/运行手册、环境变量全表、冒烟链 |
| [frontend/README.md](../frontend/README.md) | 前端工程约定、API 层用法、共享组件、路由表 |
| [doc/ncg-user-guide.html](ncg-user-guide.html) | 平台使用说明（15 章） |
| [doc/ncg-api-contract.md](ncg-api-contract.md) | 接口契约 v1.2（48 端点、22 条可执行用例、错误码文案） |
| [doc/tongncs-config-governance-detailed-design.md](tongncs-config-governance-detailed-design.md) | 技术详细设计 v1.4（§3.5 前端交互、§4.5 格式契约、§6.2 接口清单） |
| [frontend/INTEGRATION-REPORT.md](../frontend/INTEGRATION-REPORT.md) | 前后端联调报告（23/23、D1-D14、契约差异决策、复跑指引） |
| [doc/ncg-frontend-acceptance.md](ncg-frontend-acceptance.md) | 前端独立验收报告（15 章走查、反模式六轴） |

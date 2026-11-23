# agent.md — 项目上下文

> 版本：v1.5（规格基线 spec v1.4）
> 适用对象：AI 编程代理、实现者、评审者
> 本文件只说明“项目是什么、东西在哪、怎么开始”。行为红线见 [rule.md](rule.md)，需求与验收见 [spec.md](.trae/specs/formalize-governance-rules-and-acceptance/spec.md)。

## 1 项目是什么

TongNCS 配置治理平台（NCG）：底层继续使用 TongNCS 存储配置，平台只补治理能力——变更单、审批、发布编排、审计、直连检测、影响面与通知。

一句话边界：**TongNCS 是配置内容的唯一事实源，平台只拥有治理元数据，不引入第二套配置存储。**

## 2 当前阶段

规格、设计、实现与验收均已完成：Java 21 平台已落地（`src/` 142 个 Java 文件含 8 个测试类，web/worker 双 jar，构建与运行见 [README.md](README.md)）。验收结论：AC-01~26 全 PASS，`mvn test` 47/47，红线逐条有运行时/测试证据（见 [doc/acceptance-report.md](doc/acceptance-report.md) 与 [doc/implementation-traceability.md](doc/implementation-traceability.md)）。服务当前已停机、数据保留在 `.tools/pgdata`，可按 README 重新拉起。任何“已验证”的表述必须注明证据出处（验收报告 / 测试 / 追踪矩阵），不得说成无证据的运行时通过。

## 3 目录地图

```text
/workspace
├── README.md                                       构建、运行与验收手册
├── agent.md                                        本文件：项目上下文
├── rule.md                                         行为红线（MUST / MUST NOT）
├── pom.xml                                         Maven 工程（Java 21，web/worker 双 jar）
├── src/、target/                                    平台源码与构建产物
├── doc/
│   ├── nacos-config-governance-prd.html                产品需求（PRD v1.1）
│   ├── tongncs-config-governance-architecture.html     系统架构设计
│   ├── tongncs-config-governance-detailed-design.md    技术详细设计（v1.4，含 §3.5 前端交互设计、§4.5 配置格式契约）
│   ├── tongncs-config-governance-detailed-design-plan.md 详细设计工作计划
│   ├── ncg-api-contract.md                             前后端联调接口清单（v1.2，48 端点、22 条可执行用例）
│   ├── ncg-frontend-prototype.html                     前端交互原型（可点击单文件）
│   ├── ncg-user-guide.html                             平台使用说明（15 章）
│   ├── acceptance-report.md                            验收报告（AC-01~26 全 PASS）
│   ├── implementation-traceability.md                  FR→规则→AC→代码追踪矩阵
│   └── static-acceptance-report.md                     静态验收评估报告
├── .trae-html-share-packages/                        HTML 分享包
└── .trae/specs/formalize-governance-rules-and-acceptance/
    ├── spec.md                                     唯一权威规格（v1.4）
    ├── tasks.md                                    任务与进度
    └── checklist.md                                交付检查项
```

注：原 `tongncs-config-governance-frontend-design.md`（前端交互设计 v1.1）已并入详细设计 §3.5，不再单独维护；P-01～P-17 页面编号对照见 `doc/ncg-api-contract.md` §4.1。

## 4 先知道的五个概念

- **唯一事实源**：运行时配置只在 TongNCS；平台草稿、快照、Diff 都不是生效值。
- **唯一写入口**：只有 Release 发布编排可以写 TongNCS，其他模块只读。
- **适配器五方法**：`read / publish / history / subscribers / test`；`publish` 返回 `verified / rejected / indeterminate` 三态。
- **模块化单体**：无状态 Web + 单活 Worker + 元数据库，不拆微服务。
- **审计**：append-only、按日哈希链、永久保留；状态变更与审计同事务。

## 5 阅读顺序

1. `doc/nacos-config-governance-prd.html`——范围、12 项 P0 功能与产品决策。
2. `doc/tongncs-config-governance-architecture.html`——模块、接口、部署与依赖方向。
3. `doc/tongncs-config-governance-detailed-design.md`——工程结构、库表、接口、流程与事务边界。
4. `doc/ncg-api-contract.md`——48 端点联调清单、契约缺口核对与 22 条可执行用例。
5. `spec.md`——规则、约束、字段/状态/错误码、验收标准。
6. `rule.md`——动手前必看的行为红线。
7. `tasks.md` / `checklist.md`——当前任务与交付检查。

只读与任务相关的章节即可；涉及规则边界时，补读 spec 附录 A（规则）、B（架构约束）、E（字段/状态/错误码）。

## 6 工作方式

- **开始前**：确认任务对应的 R-xx 规则与 AC-xx 验收场景；确认改的是文档还是代码。
- **改文档**：先改 spec，再同步 rule.md、本文件、checklist；不允许反向覆盖 spec。
- **改代码**：遵守 rule.md；测试写在模块接口层；外部 TongNCS 调用不放进数据库事务。
- **交付说明**：写清改了什么、引用哪些 R-xx / AC-xx、证据是什么。
- **文档检查**：编号唯一；计数一致（规则 83、约束 20、AC 26、错误码 39、Requirement 16、Scenario 33）；FR-01 ~ FR-12 全覆盖；未知值标注 `[Assumption, TBD]` 或 `[TBD]`。

## 7 不要在这里做的事

- 不把本文件当规则清单；新增约束写进 `rule.md`，并引用 spec 条款。
- 不凭记忆回答“当前配置是什么”；必须读 TongNCS 或带采集时间的快照。
- 不在 spec 之外自造需求、阈值或验收标准。

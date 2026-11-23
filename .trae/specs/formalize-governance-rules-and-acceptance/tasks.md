# Tasks

- [x] Task 1: 产出《业务规则目录》rule.md
  - [x] SubTask 1.1: 从 spec.md 附录 A 全量迁移 81 条规则，保持 R-xx 编号、语义与来源 FR 不变
  - [x] SubTask 1.2: 每条规则补齐验证入口（对应 AC，或 C-xx / E.x 约束）
  - [x] SubTask 1.3: 附字段表、状态机、错误码的索引章节，引用附录 E，不复制可变数值
  - [x] SubTask 1.4: 校验编号唯一、规则计数为 81、13 个域无缺失
- [x] Task 2: 产出《实现与代理执行约束》agent.md
  - [x] SubTask 2.1: 规定必读顺序与生效条件：PRD v1.1 → 架构稿 → spec v1.2 → rule.md
  - [x] SubTask 2.2: 固化硬约束：只经 Release 写入口、状态与审计同事务、indeterminate 不自动重试、实例不可用只读、敏感值脱敏
  - [x] SubTask 2.3: 规定变更流程：说明必须引用 R-xx 与 AC-xx，同步更新 tasks/checklist，附验证证据
  - [x] SubTask 2.4: 写明文档漂移检查方式与版本同步要求
- [x] Task 3: 产出《静态验收评估报告》
  - [x] SubTask 3.1: 以 spec.md 附录 G 为基线生成独立报告，保留 16 项评估与 12 PASS / 4 BLOCKED / 0 FAIL 结论
  - [x] SubTask 3.2: 为每项结论补证据索引（章节、条款编号、配套文档）
  - [x] SubTask 3.3: 列出 4 项 BLOCKED 的责任方、关闭标准与影响的验收场景
- [x] Task 4: 建立并执行文档与规格一致性校验
  - [x] SubTask 4.1: 校验 R-xx、C-xx、AC-xx、错误码编号唯一且无断号
  - [x] SubTask 4.2: 校验每个 FR 至少 1 条规则与 1 个 AC；每个 AC 至少关联 1 条规则；无孤儿项
  - [x] SubTask 4.3: 校验所有未知数值均标注 [Assumption, TBD] 或 [TBD]，且 rule.md / agent.md / 报告无冲突表述
  - [x] SubTask 4.4: 输出缺口清单并回写 tasks.md
  - 校验结论：9 项检查全部 PASS（第 6 项修订后）；已修复 R-IMP-02、R-SYS-07、附录 F 审计归档标注；约束清单收敛到 `rule.md`，`agent.md` 仅保留项目上下文；无遗留编号或覆盖缺口。
- [x] Task 5: 评审并冻结 v1.2 基线
  - [x] SubTask 5.1: 与 PRD v1.1、系统架构设计逐条对照，确认无范围漂移
  - [x] SubTask 5.2: 对 A-01、A-03、A-07 形成结论；无法结论的保留 BLOCKED 并记录批准人
  - [x] SubTask 5.3: 记录评审结论、签署人与冻结版本，更新版本与变更记录
  - 评审结论：v1.2 文档基线冻结，签署人“用户（本会话批准）”；A-01、A-03、A-07 未形成数值结论，保留 G-13 ~ G-15 BLOCKED；G-16 交付物已产出，正式改判留待下一版静态评估。

# Task Dependencies

- Task 1 与 Task 2 可并行
- Task 3 依赖 Task 1、Task 2
- Task 4 依赖 Task 1、Task 2、Task 3
- Task 5 依赖 Task 4

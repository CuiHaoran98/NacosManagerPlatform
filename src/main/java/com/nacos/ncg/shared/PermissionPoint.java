package com.nacos.ncg.shared;

/**
 * 权限点（详细设计 v1.1 §9 / spec FR-06、R-AUTH-02：固定 10 个权限点）。
 * 授权范围 = app_id × env_id；鉴权链：会话→权限点→资源范围→职责分离→实例状态，任一失败即拒绝。
 */
public enum PermissionPoint {
    VIEW,               // 查看目录/变更单/发布记录（不含敏感明文）
    EDIT,               // 编辑草稿
    SUBMIT,             // 提交审批
    APPROVE,            // 审批（受职责分离约束）
    PUBLISH,            // 发布/重发（受职责分离与实例状态约束）
    ROLLBACK,           // 回滚
    VIEW_SENSITIVE,     // 查看敏感明文（需原因≥10 字+审计）
    VIEW_AUDIT,         // 查看审计
    EXPORT_AUDIT,       // 导出审计（异步）
    ADMIN               // 平台管理（账号/环境/实例/授权/窗口）
}

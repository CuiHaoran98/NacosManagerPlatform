package com.nacos.ncg.shared;

/**
 * 平台统一错误码：取值与语义以 spec v1.3 附录 E.3 为准（37 条，域-HTTP-序号）。
 * 可重试性以 E.3 的「可重试」列为准；HTTP 状态除 REL-409-002（幂等命中返既有记录，200）外与编码一致。
 * 新增错误码必须先改 spec，不得在此处新增。
 */
public enum NcgErrorCode {

    AUTH_401_001("AUTH-401-001", 401, Retryable.NO, "未登录或会话失效", "重新登录"),
    AUTH_401_002("AUTH-401-002", 401, Retryable.NO, "账号被禁用或冻结", "联系管理员"),
    AUTHZ_403_001("AUTHZ-403-001", 403, Retryable.NO, "缺少权限点", "申请授权"),
    AUTHZ_403_002("AUTHZ-403-002", 403, Retryable.NO, "提交人或发布人审批冲突", "更换审批人"),
    AUTHZ_403_003("AUTHZ-403-003", 403, Retryable.NO, "高风险审批人非应用负责人", "指定负责人或代理人"),
    CAT_409_001("CAT-409-001", 409, Retryable.NO, "配置已被其他应用或环境映射", "先解除原映射"),
    CAT_409_002("CAT-409-002", 409, Retryable.NO, "纳管动作与现有映射冲突", "先处理未纳管项"),
    CR_400_001("CR-400-001", 400, Retryable.NO, "必填缺失或格式错误", "修正后重新提交"),
    CR_409_001("CR-409-001", 409, Retryable.NO, "同配置已有进行中变更单", "使用已有单号"),
    CR_412_001("CR-412-001", 412, Retryable.NO, "提交或发布前基线漂移", "重新审批"),
    CR_415_001("CR-415-001", 415, Retryable.NO, "DataId 格式不支持写入", "该 DataId 格式不支持，只能只读浏览"),
    CR_422_001("CR-422-001", 422, Retryable.NO, "配置内容解析失败", "配置内容解析失败（第 N 行）"),
    CR_413_001("CR-413-001", 413, Retryable.NO, "内容超过上限", "拆分或申请调整"),
    APR_409_001("APR-409-001", 409, Retryable.NO, "变更单状态不允许审批", "刷新状态"),
    APR_422_001("APR-422-001", 422, Retryable.NO, "驳回缺少理由", "补充理由"),
    REL_403_001("REL-403-001", 403, Retryable.NO, "无发布权限", "申请授权"),
    REL_409_001("REL-409-001", 409, Retryable.NO, "变更单状态不允许发布", "刷新状态"),
    REL_409_002("REL-409-002", 200, Retryable.NO, "幂等命中，返回既有发布记录", "展示既有记录"),
    REL_412_001("REL-412-001", 412, Retryable.NO, "发布前基线不一致", "退回审批"),
    REL_422_001("REL-422-001", 422, Retryable.NO, "发布窗口外", "等待窗口或调整计划"),
    REL_502_001("REL-502-001", 502, Retryable.NO, "写入结果不确定", "人工对账"),
    REL_503_001("REL-503-001", 503, Retryable.YES, "实例不可用或连接失败", "实例恢复后重试"),
    RB_410_001("RB-410-001", 410, Retryable.NO, "超过 30 天不可恢复", "从 Nacos 历史另建变更"),
    RB_409_001("RB-409-001", 409, Retryable.NO, "恢复时同名冲突", "选择覆盖并审批或放弃"),
    AUD_500_001("AUD-500-001", 500, Retryable.YES, "审计写入失败", "整笔业务失败后重试"),
    IMP_409_001("IMP-409-001", 409, Retryable.NO, "影响面缺失阻断高风险发布", "补齐订阅映射"),
    NTF_502_001("NTF-502-001", 502, Retryable.AT_MOST_3, "通知渠道失败", "降级站内通知"),
    CON_409_001("CON-409-001", 409, Retryable.NO, "实例已被其他环境绑定", "更换实例"),
    CON_502_001("CON-502-001", 502, Retryable.YES, "连接测试失败", "修正凭证或网络"),
    SYN_409_001("SYN-409-001", 409, Retryable.NO, "目标环境已有进行中变更单", "使用目标单号"),
    SYN_409_002("SYN-409-002", 409, Retryable.NO, "跨环境映射或格式不一致", "先对齐映射"),
    SCH_409_001("SCH-409-001", 409, Retryable.NO, "定时任务状态不允许操作", "刷新状态"),
    SCH_409_002("SCH-409-002", 409, Retryable.NO, "到点复核失败", "重新计划并重新审批"),
    BAT_413_001("BAT-413-001", 413, Retryable.NO, "批量超过上限", "拆分批次"),
    SYS_403_001("SYS-403-001", 403, Retryable.NO, "不支持紧急发布", "无替代通道"),
    SYS_423_001("SYS-423-001", 423, Retryable.NO, "环境只读降级", "等待实例恢复"),
    SYS_404_001("SYS-404-001", 404, Retryable.NO, "对象不存在或已删除", "刷新列表"),
    SYS_409_001("SYS-409-001", 409, Retryable.YES, "乐观锁冲突", "重新读取后重试"),
    SYS_500_001("SYS-500-001", 500, Retryable.DEPENDS, "未分类内部错误", "上报 TraceId");

    /** E.3「可重试」列：同一请求原样重试是否安全。 */
    public enum Retryable { NO, YES, AT_MOST_3, DEPENDS }

    private final String code;
    private final int httpStatus;
    private final Retryable retryable;
    private final String trigger;
    private final String advice;

    NcgErrorCode(String code, int httpStatus, Retryable retryable, String trigger, String advice) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.trigger = trigger;
        this.advice = advice;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Retryable retryable() {
        return retryable;
    }

    public String trigger() {
        return trigger;
    }

    public String advice() {
        return advice;
    }

    /** 未知编码一律回落 SYS_500_001，不放行未定义码。 */
    public static NcgErrorCode fromCode(String code) {
        for (NcgErrorCode v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return SYS_500_001;
    }

    @Override
    public String toString() {
        return code;
    }
}

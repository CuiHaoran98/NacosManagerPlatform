package com.nacos.ncg.shared;

import java.util.UUID;

/**
 * TraceId 归一化（§6.2 X-Trace-Id 透传 + R-AUD 可追责）：
 * HTTP 链路由 TraceIdFilter 生成并传入；Worker/引导/测试等无请求上下文的写入者传 null，
 * 统一在此兜底生成（32 位去横线 UUID，与 TraceIdFilter 的兜底格式一致），
 * 保证 trace_id NOT NULL 的审计/变更/发布记录不因缺参失败。
 */
public final class TraceIds {

    private TraceIds() {
    }

    public static String orNew(String traceId) {
        return (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
    }
}

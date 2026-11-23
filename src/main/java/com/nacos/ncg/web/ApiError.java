package com.nacos.ncg.web;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误体（详细设计 v1.1 §6.1 / spec E.3）：
 * {code, message, traceId, details, timestamp}。
 */
public record ApiError(
        String code,
        String message,
        String traceId,
        Map<String, Object> details,
        Instant timestamp) {
}

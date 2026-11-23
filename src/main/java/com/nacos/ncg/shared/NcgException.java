package com.nacos.ncg.shared;

import java.util.Collections;
import java.util.Map;

/**
 * 平台业务异常：带 spec E.3 错误码与结构化 details。
 * 对外错误体形状固定为 {code,message,traceId,details,timestamp}（详细设计 v1.1 §6.1）。
 */
public class NcgException extends RuntimeException {

    private final NcgErrorCode errorCode;
    private final Map<String, Object> details;

    public NcgException(NcgErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap(), null);
    }

    public NcgException(NcgErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    public NcgException(NcgErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Collections.emptyMap() : Collections.unmodifiableMap(details);
    }

    public NcgErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}

package com.nacos.ncg.web;

import com.nacos.ncg.shared.NcgErrorCode;
import com.nacos.ncg.shared.NcgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 全局异常→统一错误体（§6.1）：NcgException 按其错误码的 HTTP 状态返回；
 * 其余异常 fail-closed 落 SYS-500-001，绝不把堆栈或内部细节回给调用方。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NcgException.class)
    public ResponseEntity<ApiError> handleNcg(NcgException e) {
        NcgErrorCode code = e.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("business failure {} : {}", code.code(), e.getMessage());
        } else {
            log.warn("business rejected {} : {}", code.code(), e.getMessage());
        }
        ApiError body = new ApiError(code.code(), e.getMessage(), traceId(), e.details(), Instant.now());
        // REL-409-002 幂等命中按设计返回 200 + 既有记录（httpStatus 字段即 200）
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        ApiError body = new ApiError(NcgErrorCode.CR_400_001.code(),
                "validation failed: " + e.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + " " + f.getDefaultMessage())
                        .findFirst().orElse("invalid request"),
                traceId(), java.util.Map.of(), Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 空 body / 非法 JSON / null 字面量：请求体不可读属客户端错误（CR-400-001），不得落 SYS-500-001。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("request body unreadable : {}", e.getMessage());
        ApiError body = new ApiError(NcgErrorCode.CR_400_001.code(),
                "请求体缺失或格式错误", traceId(), java.util.Map.of(), Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 路径不存在（含静态资源兜底）：404 SYS-404-001，不得落 SYS-500-001（契约 §6.6 禁止未预期 5xx）。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        ApiError body = new ApiError(NcgErrorCode.SYS_404_001.code(),
                "resource not found", traceId(), java.util.Map.of(), Instant.now());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Spring MVC 请求绑定/路由层客户端错误：方法不支持（POST /notify-bindings 等无映射方法）、
     * 媒体类型不支持、请求参数绑定失败、方法参数类型不匹配。契约 §6.6 禁止未预期 5xx，
     * 统一 400 CR-400-001（联调 D14：此前落 generic handler → 500 SYS-500-001）。
     */
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        log.warn("bad request (binding/routing) : {}", e.getMessage());
        ApiError body = new ApiError(NcgErrorCode.CR_400_001.code(),
                "请求方法或参数不合法", traceId(),
                java.util.Map.of("reason", String.valueOf(e.getMessage())), Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e) {
        log.error("unhandled error", e);
        ApiError body = new ApiError(NcgErrorCode.SYS_500_001.code(),
                "internal error (traceId attached)", traceId(), java.util.Map.of(), Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}

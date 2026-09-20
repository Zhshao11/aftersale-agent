package com.aftersale.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常兜底。
 *
 * 原先只在 ChatController 里处理了 IllegalArgumentException，其余异常直接走 Spring 默认错误页：
 * 既不返回可关联的 id，也不保证响应结构，用户报错时你无法把"他看到的那个报错"对回日志里的某一行。
 * 这一层补上两件事：统一响应结构 + errorId 贯穿响应与日志。
 *
 * 注意这是"最后一道网"，不是错误处理的替代品：能预期到的失败（工具超时、政策拒绝）
 * 应该在业务层返回结构化结果，而不是抛到这里变成 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        String errorId = newErrorId();
        log.warn("请求参数非法 errorId={} message={}", errorId, e.getMessage());
        return body(errorId, HttpStatus.BAD_REQUEST, "INVALID_ARGS", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
        String errorId = newErrorId();
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("请求体校验失败");
        log.warn("请求体校验失败 errorId={} message={}", errorId, message);
        return body(errorId, HttpStatus.BAD_REQUEST, "INVALID_ARGS", message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception e) {
        String errorId = newErrorId();
        log.warn("请求格式错误 errorId={} err={}", errorId, e.toString());
        return body(errorId, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "请求格式不正确，请检查 JSON 结构与参数类型");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        String errorId = newErrorId();
        log.error("未捕获异常 errorId={}", errorId, e);
        return body(errorId, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务内部错误，请把 errorId 提供给排查人员");
    }

    private ResponseEntity<Map<String, Object>> body(String errorId, HttpStatus status,
                                                     String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message == null ? code : message);
        payload.put("errorId", errorId);
        return ResponseEntity.status(status).body(payload);
    }

    private static String newErrorId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

package com.caopan.platform.config;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.i18n.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（platform-bootstrap）。
 * <p>将 {@link BizException}、参数异常、未捕获异常统一转为 {@link Result}；
 * {@link ErrorCode#UNAUTHORIZED} 额外返回 HTTP 401。错误文案固定英文。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    /**
     * @param messageSource 英文文案源
     */
    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 处理业务异常；未授权码映射为 HTTP 401，其余业务错误保持 HTTP 200 + Result.code。
     *
     * @param e       业务异常
     * @param request 当前请求
     * @return 失败 Result（带对应 HTTP 状态）
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e, HttpServletRequest request) {
        String message = resolveBizMessage(e);
        log.warn("biz exception, code={}, message={}", e.getCode(), message);
        HttpStatus status = (e.getCode() == ErrorCode.UNAUTHORIZED.getCode()
                || e.getCode() == ErrorCode.ADMIN_UNAUTHORIZED.getCode())
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), message));
    }

    /**
     * 处理参数/校验类异常 → PARAM_INVALID。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 失败 Result
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class,
            BindException.class,
            IllegalArgumentException.class
    })
    public Result<Void> handleParam(Exception e, HttpServletRequest request) {
        log.warn("param invalid, message={}", e.getMessage());
        String message = ErrorMessages.resolve(messageSource, ErrorCode.PARAM_INVALID);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    /**
     * 静态资源 / 未映射路径 404：常见于浏览器扫根路径、探活、误打 URL。
     * <p>只打 WARN，不打 ERROR/slsnotify，避免噪声误报。</p>
     *
     * @param e       Spring 6 {@code NoResourceFoundException}
     * @param request 当前请求
     * @return HTTP 404 + Result
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("resource not found, uri={}, resource={}", request.getRequestURI(), e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(40404, "Not found"));
    }

    /**
     * 兜底未处理异常 → SYSTEM_ERROR。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 失败 Result
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletRequest request) {
        log.error("unhandled exception slsnotify", e);
        String message = ErrorMessages.resolve(messageSource, ErrorCode.SYSTEM_ERROR);
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), message);
    }

    /**
     * 按错误码匹配枚举后取英文文案，无法匹配则用异常自带 message。
     */
    private String resolveBizMessage(BizException e) {
        ErrorCode matched = matchErrorCode(e.getCode());
        if (matched != null) {
            return ErrorMessages.resolve(messageSource, matched);
        }
        return e.getMessage();
    }

    private static ErrorCode matchErrorCode(int code) {
        for (ErrorCode value : ErrorCode.values()) {
            if (value.getCode() == code) {
                return value;
            }
        }
        return null;
    }
}

package com.caopan.platform.config;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.i18n.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.util.Locale;

/**
 * 全局异常处理（platform-bootstrap）。
 * <p>将 {@link BizException}、参数异常、未捕获异常统一转为 {@link Result}；
 * 错误文案按请求 lang / Accept-Language 国际化。限流与鉴权过滤器内也会复用
 * {@link #resolveLocale(HttpServletRequest)}。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    /**
     * 注入依赖构造。
     *
     * @param messageSource 国际化文案源
     */
    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 处理业务异常。
     *
     * @param e       业务异常
     * @param request 当前请求
     * @return 失败 Result
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletRequest request) {
        Locale locale = resolveLocale(request);
        String message = resolveBizMessage(e, locale);
        log.warn("biz exception, code={}, message={}", e.getCode(), message);
        return Result.fail(e.getCode(), message);
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
        String message = ErrorMessages.resolve(messageSource, ErrorCode.PARAM_INVALID, resolveLocale(request));
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), message);
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
        String message = ErrorMessages.resolve(messageSource, ErrorCode.SYSTEM_ERROR, resolveLocale(request));
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), message);
    }

    /**
     * 按错误码匹配枚举后取国际化文案，无法匹配则用异常自带 message。
     */
    private String resolveBizMessage(BizException e, Locale locale) {
        ErrorCode matched = matchErrorCode(e.getCode());
        if (matched != null) {
            return ErrorMessages.resolve(messageSource, matched, locale);
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

    /**
     * 解析请求 Locale：优先 query {@code lang}（en/zh/ch），其次 LocaleResolver / Request Locale。
     *
     * @param request HTTP 请求，可为 null
     * @return 非 null Locale，缺省简体中文
     */
    static Locale resolveLocale(HttpServletRequest request) {
        if (request != null) {
            String lang = request.getParameter("lang");
            if (lang != null) {
                String n = lang.trim().toLowerCase();
                if ("en".equals(n)) {
                    return Locale.ENGLISH;
                }
                if ("zh".equals(n) || "ch".equals(n)) {
                    return Locale.SIMPLIFIED_CHINESE;
                }
            }
            LocaleResolver resolver = RequestContextUtils.getLocaleResolver(request);
            if (resolver != null) {
                Locale locale = resolver.resolveLocale(request);
                if (locale != null) {
                    return locale;
                }
            }
            Locale requestLocale = request.getLocale();
            if (requestLocale != null) {
                return requestLocale;
            }
        }
        Locale holder = LocaleContextHolder.getLocale();
        return holder == null ? Locale.SIMPLIFIED_CHINESE : holder;
    }
}

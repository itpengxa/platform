package com.caopan.platform.common.i18n;

import com.caopan.platform.common.exception.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 错误文案国际化解析工具（platform-common，W10）。
 * <p>根据 {@link ErrorCode#getMessageKey()} 从 MessageSource 取中/英文案；
 * MessageSource 为空时回退枚举缺省中文。供 GlobalExceptionHandler、限流/鉴权过滤器共用。</p>
 */
public final class ErrorMessages {

    /** 工具类，禁止实例化 */
    private ErrorMessages() {
    }

    /**
     * 按当前线程 Locale 解析错误文案。
     *
     * @param messageSource Spring MessageSource，可为 null
     * @param errorCode     错误码枚举
     * @return 本地化文案；errorCode 为 null 时返回空串
     */
    public static String resolve(MessageSource messageSource, ErrorCode errorCode) {
        return resolve(messageSource, errorCode, LocaleContextHolder.getLocale());
    }

    /**
     * 按指定 Locale 解析错误文案。
     *
     * @param messageSource Spring MessageSource，可为 null
     * @param errorCode     错误码枚举
     * @param locale        目标语言，null 时用简体中文
     * @return 本地化文案；errorCode 为 null 时返回空串
     */
    public static String resolve(MessageSource messageSource, ErrorCode errorCode, Locale locale) {
        if (errorCode == null) {
            return "";
        }
        if (messageSource == null) {
            return errorCode.getMessage();
        }
        Locale use = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
        return messageSource.getMessage(
                errorCode.getMessageKey(),
                null,
                errorCode.getMessage(),
                use);
    }

    /**
     * 按 messageKey 直接解析（非 ErrorCode 场景）。
     *
     * @param messageSource  Spring MessageSource，可为 null
     * @param messageKey     资源键
     * @param defaultMessage 回退文案
     * @param locale         目标语言，null 时用简体中文
     * @return 本地化或回退文案
     */
    public static String resolve(MessageSource messageSource, String messageKey, String defaultMessage, Locale locale) {
        if (messageSource == null) {
            return defaultMessage;
        }
        Locale use = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
        return messageSource.getMessage(messageKey, null, defaultMessage, use);
    }
}

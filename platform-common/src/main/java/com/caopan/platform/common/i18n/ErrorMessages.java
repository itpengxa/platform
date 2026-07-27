package com.caopan.platform.common.i18n;

import com.caopan.platform.common.exception.ErrorCode;
import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * 错误文案解析工具（platform-common）。
 * <p>当前仅英文：一律按 {@link Locale#ENGLISH} 从 MessageSource 取值；
 * MessageSource 为空时回退 {@link ErrorCode#getMessage()}。
 * 供 GlobalExceptionHandler、限流过滤器共用。</p>
 */
public final class ErrorMessages {

    /** 工具类，禁止实例化 */
    private ErrorMessages() {
    }

    /**
     * 解析错误文案（英文）。
     *
     * @param messageSource Spring MessageSource，可为 null
     * @param errorCode     错误码枚举
     * @return 英文文案；errorCode 为 null 时返回空串
     */
    public static String resolve(MessageSource messageSource, ErrorCode errorCode) {
        return resolve(messageSource, errorCode, Locale.ENGLISH);
    }

    /**
     * 解析错误文案；locale 参数保留兼容，实际固定英文。
     *
     * @param messageSource Spring MessageSource，可为 null
     * @param errorCode     错误码枚举
     * @param locale        忽略，固定 ENGLISH
     * @return 英文文案；errorCode 为 null 时返回空串
     */
    public static String resolve(MessageSource messageSource, ErrorCode errorCode, Locale locale) {
        if (errorCode == null) {
            return "";
        }
        if (messageSource == null) {
            return errorCode.getMessage();
        }
        return messageSource.getMessage(
                errorCode.getMessageKey(),
                null,
                errorCode.getMessage(),
                Locale.ENGLISH);
    }

    /**
     * 按 messageKey 直接解析（非 ErrorCode 场景）。
     *
     * @param messageSource  Spring MessageSource，可为 null
     * @param messageKey     资源键
     * @param defaultMessage 回退文案
     * @param locale         忽略，固定 ENGLISH
     * @return 英文或回退文案
     */
    public static String resolve(MessageSource messageSource, String messageKey, String defaultMessage, Locale locale) {
        if (messageSource == null) {
            return defaultMessage;
        }
        return messageSource.getMessage(messageKey, null, defaultMessage, Locale.ENGLISH);
    }
}

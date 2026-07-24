package com.caopan.platform.common.i18n;

import com.caopan.platform.common.exception.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 错误文案国际化解析（W10）。
 */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    public static String resolve(MessageSource messageSource, ErrorCode errorCode) {
        return resolve(messageSource, errorCode, LocaleContextHolder.getLocale());
    }

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

    public static String resolve(MessageSource messageSource, String messageKey, String defaultMessage, Locale locale) {
        if (messageSource == null) {
            return defaultMessage;
        }
        Locale use = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
        return messageSource.getMessage(messageKey, null, defaultMessage, use);
    }
}

package com.caopan.platform.common.i18n;

import com.caopan.platform.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorMessagesTest {

    @Test
    void resolve_fallsBackToDefaultWhenMessageSourceNull() {
        assertEquals(ErrorCode.UNAUTHORIZED.getMessage(),
                ErrorMessages.resolve(null, ErrorCode.UNAUTHORIZED, Locale.ENGLISH));
    }

    @Test
    void resolve_usesMessageSource() {
        StaticMessageSource ms = new StaticMessageSource();
        ms.addMessage(ErrorCode.RATE_LIMITED.getMessageKey(), Locale.ENGLISH, "Too many requests");
        assertEquals("Too many requests",
                ErrorMessages.resolve(ms, ErrorCode.RATE_LIMITED, Locale.ENGLISH));
    }
}

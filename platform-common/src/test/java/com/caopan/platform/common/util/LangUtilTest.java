package com.caopan.platform.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LangUtilTest {

    @Test
    void resolveDisplayName_byLang() {
        assertEquals("本地", LangUtil.resolveDisplayName(null, "本地", "Local", "中文"));
        assertEquals("本地", LangUtil.resolveDisplayName("local", "本地", "Local", "中文"));
        assertEquals("Local", LangUtil.resolveDisplayName("en", "本地", "Local", "中文"));
        assertEquals("中文", LangUtil.resolveDisplayName("zh", "本地", "Local", "中文"));
        assertEquals("中文", LangUtil.resolveDisplayName("CH", "本地", "Local", "中文"));
    }

    @Test
    void resolveDisplayName_fallbackWhenPreferredBlank() {
        assertEquals("本地", LangUtil.resolveDisplayName("en", "本地", "  ", "中文"));
        assertEquals("本地", LangUtil.resolveDisplayName("zh", "本地", "Local", null));
    }

    @Test
    void firstNonBlank() {
        assertEquals("a", LangUtil.firstNonBlank("a", "b"));
        assertEquals("b", LangUtil.firstNonBlank(" ", "b"));
        assertNull(LangUtil.firstNonBlank(null, null));
    }
}

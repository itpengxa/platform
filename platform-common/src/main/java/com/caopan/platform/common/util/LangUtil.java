package com.caopan.platform.common.util;

/**
 * 2026-07-23 GEO-001 多语言展示名选取：缺省本地名
 */
public final class LangUtil {

    /**
     * 构造 LangUtil。
     */
    private LangUtil() {
    }

    /**
     * @param lang   local/en/zh/ch，缺省或非法 → 本地名
     * @param name   本地名
     * @param nameEn 英文名
     * @param nameCh 中文名
     */
    public static String resolveDisplayName(String lang, String name, String nameEn, String nameCh) {
        String normalized = lang == null ? "" : lang.trim().toLowerCase();
        return switch (normalized) {
            case "en" -> firstNonBlank(nameEn, name);
            case "zh", "ch" -> firstNonBlank(nameCh, name);
            default -> name;
        };
    }

    /**
     * firstnonblank。
     * @param primary primary
     * @param fallback fallback
     * @return 查询结果
     */
    public static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}

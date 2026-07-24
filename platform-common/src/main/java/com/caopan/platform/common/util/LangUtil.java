package com.caopan.platform.common.util;

/**
 * 多语言展示名选取工具（GEO-001）。
 * <p>按请求 lang（local/en/zh，兼容 ch）从本地名、英文名、中文名中选取 displayName；
 * 缺省或非法 lang 返回本地名。供 GeoServiceImpl 组装 VO 时调用。</p>
 */
public final class LangUtil {

    /** 工具类，禁止实例化 */
    private LangUtil() {
    }

    /**
     * 按语言偏好解析展示名。
     *
     * @param lang   local/en/zh/ch；缺省或非法 → 本地名
     * @param name   本地名
     * @param nameEn 英文名
     * @param nameCh 中文名
     * @return 选定的展示名（英文/中文缺省时回退本地名）
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
     * 取首个非空白字符串。
     *
     * @param primary  优先值
     * @param fallback primary 为空或空白时的回退值
     * @return primary（非空白）或 fallback
     */
    public static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}

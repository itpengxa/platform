package com.caopan.platform.geo.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 地理数据缓存业务键枚举（统一管理变量名 / 模板 / SCAN 模式）。
 */
public enum GeoCacheKeyType {

    COUNTRIES(
            "countries",
            "国家列表",
            "platform:geo:countries:{keyword}",
            "platform:geo:countries*",
            List.of(new Param("keyword", "关键词(可空)", false))
    ),
    CHILDREN(
            "children",
            "子级列表",
            "platform:geo:children:{parentId}",
            "platform:geo:children:*",
            List.of(new Param("parentId", "父节点 ID", true))
    ),
    PATH(
            "path",
            "祖先链",
            "platform:geo:path:{id}",
            "platform:geo:path:*",
            List.of(new Param("id", "区划 ID", true))
    ),
    REGION(
            "region",
            "区划节点",
            "platform:geo:region:{id}",
            "platform:geo:region:*",
            List.of(new Param("id", "区划 ID", true))
    ),
    TREE(
            "tree",
            "行政区划树",
            "platform:geo:tree:{countryCode}:{rootId}:{depth}",
            "platform:geo:tree:*",
            List.of(
                    new Param("countryCode", "国家 ISO2", true),
                    new Param("rootId", "根节点 ID(可空→0)", false),
                    new Param("depth", "深度(可空→0)", false)
            )
    );

    private final String code;
    private final String label;
    private final String template;
    private final String scanPattern;
    private final List<Param> params;

    GeoCacheKeyType(String code, String label, String template, String scanPattern, List<Param> params) {
        this.code = code;
        this.label = label;
        this.template = template;
        this.scanPattern = scanPattern;
        this.params = params;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getTemplate() {
        return template;
    }

    public String getScanPattern() {
        return scanPattern;
    }

    public List<Param> getParams() {
        return params;
    }

    /**
     * 按参数拼装完整 Redis key。
     */
    public String buildKey(Map<String, String> input) {
        Map<String, String> p = input == null ? Map.of() : input;
        return switch (this) {
            case COUNTRIES -> GeoCacheKeys.countries(trimToNull(p.get("keyword")));
            case CHILDREN -> GeoCacheKeys.children(requirePositiveLong(p.get("parentId"), "parentId"));
            case PATH -> GeoCacheKeys.path(requirePositiveLong(p.get("id"), "id"));
            case REGION -> GeoCacheKeys.region(requirePositiveLong(p.get("id"), "id"));
            case TREE -> GeoCacheKeys.tree(
                    requireIso2(p.get("countryCode")),
                    parseOptionalLong(p.get("rootId")),
                    parseOptionalInt(p.get("depth")));
        };
    }

    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name());
        m.put("label", label);
        m.put("template", template);
        m.put("scanPattern", scanPattern);
        m.put("variableName", "GeoCacheKeyType." + name());
        List<Map<String, Object>> paramViews = new ArrayList<>();
        for (Param param : params) {
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("name", param.name());
            pv.put("label", param.label());
            pv.put("required", param.required());
            paramViews.add(pv);
        }
        m.put("params", paramViews);
        return m;
    }

    public static List<Map<String, Object>> catalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GeoCacheKeyType t : values()) {
            list.add(t.toView());
        }
        return list;
    }

    public static GeoCacheKeyType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim();
        for (GeoCacheKeyType t : values()) {
            if (t.code.equalsIgnoreCase(c) || t.name().equalsIgnoreCase(c)) {
                return t;
            }
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String requireIso2(String raw) {
        String cc = trimToNull(raw);
        if (cc == null || cc.length() != 2) {
            throw new IllegalArgumentException("countryCode 必须为 2 位 ISO2");
        }
        return cc.toUpperCase(Locale.ROOT);
    }

    private static Long requirePositiveLong(String raw, String field) {
        Long n = parseOptionalLong(raw);
        if (n == null || n <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return n;
    }

    private static Long parseOptionalLong(String raw) {
        String t = trimToNull(raw);
        if (t == null) {
            return null;
        }
        return Long.parseLong(t);
    }

    private static Integer parseOptionalInt(String raw) {
        String t = trimToNull(raw);
        if (t == null) {
            return null;
        }
        return Integer.parseInt(t);
    }

    public record Param(String name, String label, boolean required) {
    }
}

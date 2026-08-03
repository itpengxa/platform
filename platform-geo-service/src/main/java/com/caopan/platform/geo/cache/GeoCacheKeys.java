package com.caopan.platform.geo.cache;

import java.time.Duration;

/**
 * 行政区划三级缓存的 Key 拼装与 L2 TTL 文档常量（GEO-001）。
 * <p>Key 不含 lang，保证同一原始数据多语言共享缓存。
 * 实际 TTL 以 {@link GeoCacheProperties} 为准（可叠加抖动）。</p>
 */
public final class GeoCacheKeys {

    /** 缓存键统一前缀 */
    public static final String PREFIX = "platform:geo:";

    /** 地理数据键模式（不含限流 rl / 鉴权） */
    public static final String PATTERN_COUNTRIES = PREFIX + "countries*";
    public static final String PATTERN_CHILDREN = PREFIX + "children:*";
    public static final String PATTERN_PATH = PREFIX + "path:*";
    public static final String PATTERN_REGION = PREFIX + "region:*";
    public static final String PATTERN_TREE = PREFIX + "tree:*";

    /** 全量数据清理用的白名单前缀模式（严禁 platform:geo:* 一把梭） */
    public static final java.util.List<String> DATA_CLEAR_PATTERNS = java.util.List.of(
            PATTERN_COUNTRIES, PATTERN_CHILDREN, PATTERN_PATH, PATTERN_REGION, PATTERN_TREE);

    /** 业务键目录（变量名 / 模板 / 参数），供管理端下拉。 */
    public static java.util.List<java.util.Map<String, Object>> catalog() {
        return GeoCacheKeyType.catalog();
    }

    /** L1 Caffeine 默认过期（由 CacheConfig 配置；此处作文档常量） */
    public static final Duration L1_TTL = Duration.ofMinutes(10);
    /** 国家列表 L2 TTL */
    public static final Duration L2_COUNTRIES_TTL = Duration.ofHours(24);
    /** 子级列表 L2 TTL */
    public static final Duration L2_CHILDREN_TTL = Duration.ofHours(24);
    /** 祖先链 L2 TTL */
    public static final Duration L2_PATH_TTL = Duration.ofHours(24);
    /** 子树 L2 TTL（相对更短，防大对象脏读） */
    public static final Duration L2_TREE_TTL = Duration.ofHours(12);

    /** 工具类，禁止实例化 */
    private GeoCacheKeys() {
    }

    /**
     * 国家列表缓存键。
     *
     * @param keyword 关键词，可空
     * @return Redis/Caffeine key
     */
    public static String countries(String keyword) {
        String kw = keyword == null ? "" : keyword;
        return PREFIX + "countries:" + kw;
    }

    /**
     * 子级列表缓存键。
     *
     * @param parentId 父节点 ID
     * @return Redis/Caffeine key
     */
    public static String children(Long parentId) {
        return PREFIX + "children:" + parentId;
    }

    /**
     * 祖先链缓存键。
     *
     * @param id 区划 ID
     * @return Redis/Caffeine key
     */
    public static String path(Long id) {
        return PREFIX + "path:" + id;
    }

    /**
     * 单节点缓存键。
     *
     * @param id 区划 ID
     * @return Redis/Caffeine key
     */
    public static String region(Long id) {
        return PREFIX + "region:" + id;
    }

    /**
     * 行政区划树缓存键。
     *
     * @param countryCode 国家 ISO2
     * @param rootId      根节点，可空
     * @param depth       深度，可空
     * @return Redis/Caffeine key
     */
    public static String tree(String countryCode, Long rootId, Integer depth) {
        long rid = rootId == null ? 0L : rootId;
        int d = depth == null ? 0 : depth;
        return PREFIX + "tree:" + countryCode + ":" + rid + ":" + d;
    }

    /**
     * 某国树缓存 SCAN 模式。
     */
    public static String treeCountryPattern(String countryCode) {
        String cc = countryCode == null ? "" : countryCode.trim().toUpperCase();
        return PREFIX + "tree:" + cc + ":*";
    }

    /**
     * 是否为地理数据缓存键（排除限流等）。
     */
    public static boolean isGeoDataKey(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return false;
        }
        String rest = key.substring(PREFIX.length());
        if (rest.startsWith("rl:")) {
            return false;
        }
        return rest.startsWith("countries")
                || rest.startsWith("children:")
                || rest.startsWith("path:")
                || rest.startsWith("region:")
                || rest.startsWith("tree:");
    }
}

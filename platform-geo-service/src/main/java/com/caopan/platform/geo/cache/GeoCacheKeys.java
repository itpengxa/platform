package com.caopan.platform.geo.cache;

import java.time.Duration;

/**
 * 行政区划三级缓存的 Key 拼装与 L2 TTL 常量。
 * <p>Key 不含 lang，保证同一原始数据多语言共享缓存。</p>
 */
public final class GeoCacheKeys {

    /** 缓存键统一前缀 */
    public static final String PREFIX = "platform:geo:";

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
}

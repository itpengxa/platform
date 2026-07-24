package com.caopan.platform.geo.cache;

import java.time.Duration;

/**
 * 2026-07-24 GEO-001 三级缓存 Key / TTL
 */
public final class GeoCacheKeys {

    public static final String PREFIX = "platform:geo:";

    public static final Duration L1_TTL = Duration.ofMinutes(10);
    public static final Duration L2_COUNTRIES_TTL = Duration.ofHours(24);
    public static final Duration L2_CHILDREN_TTL = Duration.ofHours(24);
    public static final Duration L2_PATH_TTL = Duration.ofHours(24);
    public static final Duration L2_TREE_TTL = Duration.ofHours(12);

    private GeoCacheKeys() {
    }

    public static String countries(String keyword) {
        String kw = keyword == null ? "" : keyword;
        return PREFIX + "countries:" + kw;
    }

    public static String children(Long parentId) {
        return PREFIX + "children:" + parentId;
    }

    public static String path(Long id) {
        return PREFIX + "path:" + id;
    }

    public static String tree(String countryCode, Long rootId, Integer depth) {
        long rid = rootId == null ? 0L : rootId;
        int d = depth == null ? 0 : depth;
        return PREFIX + "tree:" + countryCode + ":" + rid + ":" + d;
    }
}

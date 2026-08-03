package com.caopan.platform.geo.cache;

import java.util.List;

/**
 * 集群 L1 缓存失效广播消息。
 */
public record CacheInvalidationMessage(
        String fromInstanceId,
        String scope,
        String countryCode,
        Long regionId,
        Long parentId,
        List<String> keys,
        long ts
) {
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_COUNTRY = "COUNTRY";
    public static final String SCOPE_REGION = "REGION";
    public static final String SCOPE_KEYS = "KEYS";
}

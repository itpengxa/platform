package com.caopan.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IP 限流配置（JDK21 record + 构造器绑定）。
 */
@ConfigurationProperties(prefix = "platform.geo.rate-limit")
public record GeoRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean trustForwardedHeaders,
        @DefaultValue("false") boolean failClosed,
        @DefaultValue("1000") long defaultIntervalMs,
        @DefaultValue("1000") long searchIntervalMs,
        @DefaultValue("2000") long treeIntervalMs
) {
    public long resolvedDefaultIntervalMs() {
        return Math.max(defaultIntervalMs, 1L);
    }

    public long resolvedSearchIntervalMs() {
        return Math.max(searchIntervalMs, 1L);
    }

    public long resolvedTreeIntervalMs() {
        return Math.max(treeIntervalMs, 1L);
    }
}

package com.caopan.platform.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 缺省上报 / 地图校验配置（GEO-002）。
 */
@ConfigurationProperties(prefix = "platform.geo.report")
public record GeoReportProperties(
        @DefaultValue("nominatim") String geocodeProvider,
        @DefaultValue("") String googleApiKey,
        @DefaultValue("50") double maxParentDistanceKm,
        @DefaultValue("true") boolean autoCreateEnabled,
        @DefaultValue("30") int rateLimitPerTokenPerHour,
        @DefaultValue("platform-geo-report/1.0") String nominatimUserAgent
) {
    public String normalizedProvider() {
        String p = geocodeProvider == null ? "nominatim" : geocodeProvider.trim().toLowerCase();
        return p.isEmpty() ? "nominatim" : p;
    }
}

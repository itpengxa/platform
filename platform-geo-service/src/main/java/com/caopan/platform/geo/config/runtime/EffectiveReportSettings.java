package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

@Component
public class EffectiveReportSettings {

    private final EffectiveConfigRegistry registry;

    public EffectiveReportSettings(EffectiveConfigRegistry registry) {
        this.registry = registry;
    }

    public String geocodeProvider() {
        return registry.getOrDefault("platform.geo.report.geocode-provider", "nominatim");
    }

    public String googleApiKey() {
        return registry.getOrDefault("platform.geo.report.google-api-key", "");
    }

    public double maxParentDistanceKm() {
        return registry.getDouble("platform.geo.report.max-parent-distance-km", 50d);
    }

    public boolean autoCreateEnabled() {
        return registry.getBool("platform.geo.report.auto-create-enabled", true);
    }

    public int rateLimitPerTokenPerHour() {
        return registry.getInt("platform.geo.report.rate-limit-per-token-per-hour", 30);
    }

    public String nominatimUserAgent() {
        return registry.getOrDefault("platform.geo.report.nominatim-user-agent", "platform-geo-report/1.0");
    }

    public String normalizedProvider() {
        String p = geocodeProvider() == null ? "nominatim" : geocodeProvider().trim().toLowerCase();
        return p.isEmpty() ? "nominatim" : p;
    }
}

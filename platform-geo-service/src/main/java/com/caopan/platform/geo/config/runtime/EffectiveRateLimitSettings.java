package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

@Component
public class EffectiveRateLimitSettings {

    private final EffectiveConfigRegistry registry;

    public EffectiveRateLimitSettings(EffectiveConfigRegistry registry) {
        this.registry = registry;
    }

    public boolean enabled() {
        return registry.getBool("platform.geo.rate-limit.enabled", true);
    }

    public boolean trustForwardedHeaders() {
        return registry.getBool("platform.geo.rate-limit.trust-forwarded-headers", false);
    }

    public boolean failClosed() {
        return registry.getBool("platform.geo.rate-limit.fail-closed", false);
    }

    public long defaultIntervalMs() {
        return Math.max(registry.getLong("platform.geo.rate-limit.default-interval-ms", 1000L), 1L);
    }

    public long searchIntervalMs() {
        return Math.max(registry.getLong("platform.geo.rate-limit.search-interval-ms", 1000L), 1L);
    }

    public long treeIntervalMs() {
        return Math.max(registry.getLong("platform.geo.rate-limit.tree-interval-ms", 2000L), 1L);
    }
}

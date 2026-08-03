package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class EffectiveCacheSettings {

    private final EffectiveConfigRegistry registry;

    public EffectiveCacheSettings(EffectiveConfigRegistry registry) {
        this.registry = registry;
    }

    public boolean redisEnabled() {
        return registry.getBool("platform.geo.cache.redis-enabled", true);
    }

    public long l1MaximumSize() {
        return registry.getLong("platform.geo.cache.l1-maximum-size", 10000L);
    }

    public long l1TtlMinutes() {
        return Math.max(registry.getLong("platform.geo.cache.l1-ttl-minutes", 10L), 1L);
    }

    public Duration l1Ttl() {
        return Duration.ofMinutes(l1TtlMinutes());
    }

    public Duration countriesTtl() {
        return withJitter(Duration.ofHours(Math.max(registry.getLong("platform.geo.cache.countries-ttl-hours", 24L), 1L)));
    }

    public Duration childrenTtl() {
        return withJitter(Duration.ofHours(Math.max(registry.getLong("platform.geo.cache.children-ttl-hours", 24L), 1L)));
    }

    public Duration pathTtl() {
        return withJitter(Duration.ofHours(Math.max(registry.getLong("platform.geo.cache.path-ttl-hours", 24L), 1L)));
    }

    public Duration regionTtl() {
        return withJitter(Duration.ofHours(Math.max(registry.getLong("platform.geo.cache.region-ttl-hours", 24L), 1L)));
    }

    public Duration treeTtl() {
        return withJitter(Duration.ofHours(Math.max(registry.getLong("platform.geo.cache.tree-ttl-hours", 12L), 1L)));
    }

    public Duration negativeTtl() {
        return Duration.ofSeconds(Math.max(registry.getLong("platform.geo.cache.negative-ttl-seconds", 30L), 1L));
    }

    public long jitterSeconds() {
        return Math.max(registry.getLong("platform.geo.cache.jitter-seconds", 300L), 0L);
    }

    public int treeMaxRows() {
        int v = registry.getInt("platform.geo.cache.tree-max-rows", 20_000);
        return v <= 0 ? 20_000 : v;
    }

    public int treeCountryMaxDepth() {
        int v = registry.getInt("platform.geo.cache.tree-country-max-depth", 4);
        return v < 1 || v > 5 ? 4 : v;
    }

    private Duration withJitter(Duration base) {
        long jitter = jitterSeconds();
        if (jitter == 0L || base == null || base.isZero() || base.isNegative()) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitter + 1));
    }
}

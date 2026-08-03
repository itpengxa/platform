package com.caopan.platform.geo.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * geo 缓存配置（JDK21 record + 构造器绑定）。
 * <p>前缀 {@code platform.geo.cache}；不可变，由 Spring Boot 构造器注入绑定。</p>
 */
@ConfigurationProperties(prefix = "platform.geo.cache")
public record GeoCacheProperties(
        @DefaultValue("true") boolean redisEnabled,
        @DefaultValue("true") boolean l1Enabled,
        @DefaultValue("10000") long l1MaximumSize,
        @DefaultValue("10") long l1TtlMinutes,
        @DefaultValue("24") long countriesTtlHours,
        @DefaultValue("24") long childrenTtlHours,
        @DefaultValue("24") long pathTtlHours,
        @DefaultValue("24") long regionTtlHours,
        @DefaultValue("12") long treeTtlHours,
        @DefaultValue("300") long jitterSeconds,
        @DefaultValue("30") long negativeTtlSeconds,
        @DefaultValue("20000") int treeMaxRows,
        @DefaultValue("4") int treeCountryMaxDepth
) {

    public Duration l1Ttl() {
        return Duration.ofMinutes(Math.max(l1TtlMinutes, 1L));
    }

    public Duration countriesTtl() {
        return withJitter(Duration.ofHours(Math.max(countriesTtlHours, 1L)));
    }

    public Duration childrenTtl() {
        return withJitter(Duration.ofHours(Math.max(childrenTtlHours, 1L)));
    }

    public Duration pathTtl() {
        return withJitter(Duration.ofHours(Math.max(pathTtlHours, 1L)));
    }

    public Duration regionTtl() {
        return withJitter(Duration.ofHours(Math.max(regionTtlHours, 1L)));
    }

    public Duration treeTtl() {
        return withJitter(Duration.ofHours(Math.max(treeTtlHours, 1L)));
    }

    public Duration negativeTtl() {
        return Duration.ofSeconds(Math.max(negativeTtlSeconds, 1L));
    }

    public int resolvedTreeMaxRows() {
        return treeMaxRows <= 0 ? 20_000 : treeMaxRows;
    }

    public int resolvedTreeCountryMaxDepth() {
        return treeCountryMaxDepth < 1 || treeCountryMaxDepth > 5 ? 4 : treeCountryMaxDepth;
    }

    public Duration withJitter(Duration base) {
        long jitter = Math.max(jitterSeconds, 0L);
        if (jitter == 0L || base == null || base.isZero() || base.isNegative()) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitter + 1));
    }
}

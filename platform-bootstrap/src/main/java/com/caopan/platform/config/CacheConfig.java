package com.caopan.platform.config;

import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.cache.TieredCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 三级缓存装配（GEO-001 / platform-bootstrap）。
 * <p>创建 L1 Caffeine 与 {@link TieredCache}（L1→L2 Redis→L3 DB）；
 * TTL/容量来自 {@link GeoCacheProperties}。负缓存条目使用与 L2 一致的短 TTL，正缓存用 L1 TTL。
 * Redis 不可用或关闭时降级 L1+DB。</p>
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public Cache<String, Object> geoLocalCache(GeoCacheProperties props) {
        long positiveNanos = props.l1Ttl().toNanos();
        long negativeNanos = props.negativeTtl().toNanos();
        return Caffeine.newBuilder()
                .maximumSize(Math.max(props.l1MaximumSize(), 100L))
                .expireAfter(new Expiry<String, Object>() {
                    @Override
                    public long expireAfterCreate(String key, Object value, long currentTime) {
                        return value == TieredCache.NULL_SENTINEL ? negativeNanos : positiveNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, Object value, long currentTime, long currentDuration) {
                        return value == TieredCache.NULL_SENTINEL ? negativeNanos : positiveNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, Object value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .recordStats()
                .build();
    }

    @Bean
    public TieredCache tieredCache(
            Cache<String, Object> geoLocalCache,
            ObjectMapper objectMapper,
            GeoCacheProperties props,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        boolean useRedis = props.redisEnabled() && redis != null;
        if (!useRedis) {
            log.warn("TieredCache Redis L2 disabled, fallback L1+DB only");
        } else {
            log.info("TieredCache enabled: L1=Caffeine(max={}, ttl={}m, neg={}s), L2=Redis(jitter={}s), L3=DB",
                    props.l1MaximumSize(), props.l1TtlMinutes(),
                    props.negativeTtlSeconds(), props.jitterSeconds());
        }
        return new TieredCache(geoLocalCache, redis, objectMapper, useRedis, props.negativeTtl());
    }
}

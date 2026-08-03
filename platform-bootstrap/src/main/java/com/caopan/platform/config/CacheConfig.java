package com.caopan.platform.config;

import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.cache.TieredCache;
import com.caopan.platform.geo.config.runtime.EffectiveCacheSettings;
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
 * L1/L2 运行时开关见 {@link EffectiveCacheSettings}。</p>
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
            EffectiveCacheSettings effectiveCacheSettings,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        boolean redisAvailable = redis != null;
        if (!redisAvailable) {
            log.warn("TieredCache Redis client unavailable; L2 cannot be enabled");
        } else {
            log.info("TieredCache assembled: L1=Caffeine(max={}, ttl={}m), L2=Redis(available), switches via runtime config",
                    props.l1MaximumSize(), props.l1TtlMinutes());
        }
        return new TieredCache(
                geoLocalCache, redis, objectMapper, redisAvailable, props.negativeTtl(), effectiveCacheSettings);
    }
}

package com.caopan.platform.config;

import com.caopan.platform.geo.cache.GeoCacheKeys;
import com.caopan.platform.geo.cache.TieredCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 2026-07-24 GEO-001 三级缓存装配：Caffeine L1 + Redis L2
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public Cache<String, Object> geoLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(GeoCacheKeys.L1_TTL.toMinutes(), TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    @Bean
    public TieredCache tieredCache(
            Cache<String, Object> geoLocalCache,
            ObjectMapper objectMapper,
            @Value("${platform.geo.cache.redis-enabled:true}") boolean redisEnabled,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        boolean useRedis = redisEnabled && redis != null;
        if (!useRedis) {
            log.warn("TieredCache Redis L2 disabled, fallback L1+DB only");
        } else {
            log.info("TieredCache enabled: L1=Caffeine, L2=Redis, L3=DB; L1 warm via virtual thread");
        }
        return new TieredCache(geoLocalCache, redis, objectMapper, useRedis);
    }

    /**
     * 无 Redis 时保证 ObjectMapper 仍可用（Boot 通常已有）
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}

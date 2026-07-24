package com.caopan.platform.config;

import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.cache.TieredCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine (L1) + Redis (L2) 装配；TTL/容量来自 {@link GeoCacheProperties}。
 */
@Configuration
@EnableConfigurationProperties(GeoCacheProperties.class)
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public Cache<String, Object> geoLocalCache(GeoCacheProperties props) {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(props.getL1MaximumSize(), 100L))
                .expireAfterWrite(Math.max(props.getL1TtlMinutes(), 1L), TimeUnit.MINUTES)
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
        boolean useRedis = props.isRedisEnabled() && redis != null;
        if (!useRedis) {
            log.warn("TieredCache Redis L2 disabled, fallback L1+DB only");
        } else {
            log.info("TieredCache enabled: L1=Caffeine(max={}, ttl={}m), L2=Redis(jitter={}s), L3=DB",
                    props.getL1MaximumSize(), props.getL1TtlMinutes(), props.getJitterSeconds());
        }
        return new TieredCache(geoLocalCache, redis, objectMapper, useRedis, props.negativeTtl());
    }
}

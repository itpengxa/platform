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
 * 三级缓存装配（GEO-001 / platform-bootstrap）。
 * <p>创建 L1 Caffeine 与 {@link TieredCache}（L1→L2 Redis→L3 DB）；
 * TTL/容量来自 {@link GeoCacheProperties}。Redis 不可用或关闭时降级 L1+DB。</p>
 */
@Configuration
@EnableConfigurationProperties(GeoCacheProperties.class)
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * L1 本地缓存 Bean。
     *
     * @param props 缓存配置
     * @return Caffeine Cache
     */
    @Bean
    public Cache<String, Object> geoLocalCache(GeoCacheProperties props) {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(props.getL1MaximumSize(), 100L))
                .expireAfterWrite(Math.max(props.getL1TtlMinutes(), 1L), TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * 三级缓存门面 Bean。
     *
     * @param geoLocalCache  L1
     * @param objectMapper   JSON
     * @param props          TTL/开关
     * @param redisProvider  可选 Redis
     * @return TieredCache
     */
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

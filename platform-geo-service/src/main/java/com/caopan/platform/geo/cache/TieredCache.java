package com.caopan.platform.geo.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 2026-07-24 GEO-001 三级缓存：本地 Caffeine → Redis → DB。
 * L2 命中或 DB 回源后，用虚拟线程异步回填 L1，避免阻塞请求线程。
 */
public class TieredCache {

    private static final Logger log = LoggerFactory.getLogger(TieredCache.class);

    private final Cache<String, Object> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean redisEnabled;

    public TieredCache(Cache<String, Object> localCache,
                       StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper,
                       boolean redisEnabled) {
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisEnabled = redisEnabled && redisTemplate != null;
    }

    public <T> T get(String key, TypeReference<T> type, Duration l2Ttl, Supplier<T> dbLoader) {
        Object local = localCache.getIfPresent(key);
        if (local != null) {
            log.debug("L1 hit, key={}", key);
            return cast(local, type);
        }

        T fromRedis = getFromRedis(key, type);
        if (fromRedis != null) {
            log.debug("L2 hit, key={}", key);
            asyncPutLocal(key, fromRedis);
            return fromRedis;
        }

        log.debug("L3 load, key={}", key);
        T fromDb = dbLoader.get();
        if (fromDb == null) {
            return null;
        }
        putRedis(key, fromDb, l2Ttl);
        asyncPutLocal(key, fromDb);
        return fromDb;
    }

    public void evict(String key) {
        localCache.invalidate(key);
        if (!redisEnabled) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("L2 evict failed, key={}, err={}", key, e.toString());
        }
    }

    private <T> T getFromRedis(String key, TypeReference<T> type) {
        if (!redisEnabled) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("L2 get failed, degrade to DB, key={}, err={}", key, e.toString());
            return null;
        }
    }

    private void putRedis(String key, Object value, Duration ttl) {
        if (!redisEnabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("L2 put failed, key={}, err={}", key, e.toString());
        }
    }

    /**
     * 虚拟线程异步回填本地缓存（协程式同步到本地）
     */
    private void asyncPutLocal(String key, Object value) {
        Thread.startVirtualThread(() -> {
            try {
                localCache.put(key, value);
                log.debug("L1 async warm done, key={}", key);
            } catch (Exception e) {
                log.warn("L1 async warm failed, key={}, err={}", key, e.toString());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        // L1 存的是原对象；若类型已匹配直接返回
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return objectMapper.convertValue(value, type);
        }
    }
}

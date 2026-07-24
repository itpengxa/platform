package com.caopan.platform.geo.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * 三级缓存：Caffeine (L1) → Redis (L2) → DB (L3)。
 * L3 per-key singleflight 防击穿；null 短 TTL 负缓存防穿透。
 */
public class TieredCache {

    private static final Logger log = LoggerFactory.getLogger(TieredCache.class);

    /** L1 负缓存哨兵（表示 DB 明确无数据） */
    static final Object NULL_SENTINEL = new Object();
    private static final String REDIS_NULL = "__NULL__";

    private final Cache<String, Object> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean redisEnabled;
    private final Duration negativeTtl;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    public TieredCache(Cache<String, Object> localCache,
                       StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper,
                       boolean redisEnabled,
                       Duration negativeTtl) {
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisEnabled = redisEnabled && redisTemplate != null;
        this.negativeTtl = negativeTtl == null || negativeTtl.isZero() || negativeTtl.isNegative()
                ? Duration.ofSeconds(30)
                : negativeTtl;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, TypeReference<T> type, Duration l2Ttl, Supplier<T> dbLoader) {
        Object local = localCache.getIfPresent(key);
        if (local != null) {
            if (local == NULL_SENTINEL) {
                log.debug("L1 negative hit, key={}", key);
                return null;
            }
            log.debug("L1 hit, key={}", key);
            return cast(local, type);
        }

        RedisGet<T> redisGet = getFromRedis(key, type);
        if (redisGet.hit) {
            if (redisGet.negative) {
                log.debug("L2 negative hit, key={}", key);
                localCache.put(key, NULL_SENTINEL);
                return null;
            }
            log.debug("L2 hit, key={}", key);
            asyncPutLocal(key, redisGet.value);
            return redisGet.value;
        }

        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, created);
        if (existing != null) {
            log.debug("L3 singleflight wait, key={}", key);
            try {
                Object joined = existing.join();
                if (joined == NULL_SENTINEL) {
                    return null;
                }
                return cast(joined, type);
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException(cause);
            }
        }

        try {
            log.debug("L3 load, key={}", key);
            T fromDb = dbLoader.get();
            if (fromDb != null) {
                putRedis(key, fromDb, l2Ttl);
                asyncPutLocal(key, fromDb);
                created.complete(fromDb);
            } else {
                putNegative(key);
                created.complete(NULL_SENTINEL);
            }
            return fromDb;
        } catch (Throwable t) {
            created.completeExceptionally(t);
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(t);
        } finally {
            inflight.remove(key, created);
        }
    }

    public void put(String key, Object value, Duration l2Ttl) {
        if (value == null) {
            putNegative(key);
            return;
        }
        putRedis(key, value, l2Ttl);
        localCache.put(key, value);
    }

    public void putNegative(String key) {
        localCache.put(key, NULL_SENTINEL);
        if (!redisEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, REDIS_NULL, negativeTtl);
        } catch (Exception e) {
            log.warn("L2 negative put failed, key={}, err={}", key, e.toString());
        }
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

    private static final class RedisGet<T> {
        final boolean hit;
        final boolean negative;
        final T value;

        RedisGet(boolean hit, boolean negative, T value) {
            this.hit = hit;
            this.negative = negative;
            this.value = value;
        }
    }

    private <T> RedisGet<T> getFromRedis(String key, TypeReference<T> type) {
        if (!redisEnabled) {
            return new RedisGet<>(false, false, null);
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return new RedisGet<>(false, false, null);
            }
            if (REDIS_NULL.equals(json)) {
                return new RedisGet<>(true, true, null);
            }
            return new RedisGet<>(true, false, objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("L2 get failed, degrade to DB, key={}, err={}", key, e.toString());
            return new RedisGet<>(false, false, null);
        }
    }

    private void putRedis(String key, Object value, Duration ttl) {
        if (!redisEnabled) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            Duration useTtl = ttl == null || ttl.isZero() || ttl.isNegative()
                    ? Duration.ofHours(1)
                    : ttl;
            redisTemplate.opsForValue().set(key, json, useTtl);
        } catch (Exception e) {
            log.warn("L2 put failed, key={}, err={}", key, e.toString());
        }
    }

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
        if (value == null || value == NULL_SENTINEL) {
            return null;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return objectMapper.convertValue(value, type);
        }
    }
}

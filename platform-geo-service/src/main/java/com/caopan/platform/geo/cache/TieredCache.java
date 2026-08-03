package com.caopan.platform.geo.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * 三级缓存实现（GEO-001 / platform-geo-service）。
 * <p>读路径：Caffeine (L1) → Redis (L2) → DB Loader (L3)。
 * L3 对同一 key 做 singleflight 防击穿；DB miss 写入短 TTL 负缓存防穿透。
 * Redis 故障时降级为 L1+DB。由 bootstrap {@code CacheConfig} 装配。</p>
 */
public class TieredCache {

    private static final Logger log = LoggerFactory.getLogger(TieredCache.class);

    /** L1 负缓存哨兵（表示 DB 明确无数据）；L1 对该值使用短 TTL（与 L2 负缓存对齐） */
    public static final Object NULL_SENTINEL = new Object();
    private static final String REDIS_NULL = "__NULL__";

    private final Cache<String, Object> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean redisEnabled;
    private final Duration negativeTtl;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    /**
     * 注入依赖构造。
     *
     * @param localCache     L1 Caffeine
     * @param redisTemplate  L2 Redis，可为 null（关闭 L2）
     * @param objectMapper   JSON 序列化
     * @param redisEnabled   是否启用 Redis L2
     * @param negativeTtl    负缓存 TTL
     */
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

    /**
     * 三级读取：L1 → L2 → L3(dbLoader)；命中后按层回填。
     *
     * @param key      缓存键
     * @param type     反序列化类型
     * @param l2Ttl    L2 TTL（可带抖动）
     * @param dbLoader L3 加载器；返回 null 则写负缓存
     * @param <T>      值类型
     * @return 缓存值；负缓存命中返回 null
     */
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

    /**
     * 主动写入 L1+L2（value 为 null 时改写负缓存）。
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param l2Ttl L2 TTL
     */
    public void put(String key, Object value, Duration l2Ttl) {
        if (value == null) {
            putNegative(key);
            return;
        }
        putRedis(key, value, l2Ttl);
        localCache.put(key, value);
    }

    /**
     * 写入负缓存（L1 哨兵 + L2 {@code __NULL__}）。
     *
     * @param key 缓存键
     */
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

    /**
     * 同时失效 L1 与 L2。
     *
     * @param key 缓存键
     */
    public void evict(String key) {
        invalidateLocal(key);
        deleteRedisKey(key);
    }

    /** 仅失效本机 L1。 */
    public void invalidateLocal(String key) {
        if (key != null) {
            localCache.invalidate(key);
        }
    }

    /** 批量仅失效本机 L1。 */
    public void invalidateLocal(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        localCache.invalidateAll(keys);
    }

    /** 清空本机 L1（本 Bean 仅承载 geo 数据缓存）。 */
    public void invalidateLocalAll() {
        localCache.invalidateAll();
    }

    public boolean redisEnabled() {
        return redisEnabled;
    }

    public long localEstimatedSize() {
        return localCache.estimatedSize();
    }

    /**
     * 按 Redis SCAN 模式删除 L2 键；不触碰 L1。
     *
     * @param patterns Redis MATCH 模式列表
     * @param dryRun   true 只计数不删除
     * @return 匹配（或已删除）键数量
     */
    public long clearRedisByPatterns(Collection<String> patterns, boolean dryRun) {
        if (!redisEnabled || patterns == null || patterns.isEmpty()) {
            return 0L;
        }
        Set<String> matched = scanRedisKeys(patterns);
        if (dryRun || matched.isEmpty()) {
            return matched.size();
        }
        return deleteRedisKeys(matched);
    }

    /**
     * SCAN 匹配键（排除 rl）；供 dryRun / 清理复用。
     */
    public Set<String> scanRedisKeys(Collection<String> patterns) {
        Set<String> matched = new HashSet<>();
        if (!redisEnabled || patterns == null) {
            return matched;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            try {
                ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
                try (Cursor<String> cursor = redisTemplate.scan(options)) {
                    while (cursor.hasNext()) {
                        matched.add(cursor.next());
                    }
                }
            } catch (Exception e) {
                log.warn("L2 scan failed, pattern={}, err={}", pattern, e.toString());
            }
        }
        matched.removeIf(k -> k == null || k.startsWith(GeoCacheKeys.PREFIX + "rl:") || !GeoCacheKeys.isGeoDataKey(k));
        return matched;
    }

    /**
     * 精确删除 L2 键列表；跳过非 geo 数据键与 rl。
     */
    public long deleteRedisKeysExact(Collection<String> keys, boolean dryRun) {
        if (!redisEnabled || keys == null || keys.isEmpty()) {
            return 0L;
        }
        List<String> safe = new ArrayList<>();
        for (String key : keys) {
            if (GeoCacheKeys.isGeoDataKey(key)) {
                safe.add(key);
            }
        }
        if (dryRun || safe.isEmpty()) {
            return safe.size();
        }
        return deleteRedisKeys(safe);
    }

    private void deleteRedisKey(String key) {
        if (!redisEnabled || key == null) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("L2 evict failed, key={}, err={}", key, e.toString());
        }
    }

    private long deleteRedisKeys(Collection<String> keys) {
        if (!redisEnabled || keys == null || keys.isEmpty()) {
            return 0L;
        }
        try {
            Long n = redisTemplate.delete(keys);
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.warn("L2 batch delete failed, size={}, err={}", keys.size(), e.toString());
            long deleted = 0L;
            for (String key : keys) {
                try {
                    Boolean ok = redisTemplate.delete(key);
                    if (Boolean.TRUE.equals(ok)) {
                        deleted++;
                    }
                } catch (Exception ignored) {
                    // continue
                }
            }
            return deleted;
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

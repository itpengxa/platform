package com.caopan.platform.geo.admin;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.cache.CacheInvalidationBroadcaster;
import com.caopan.platform.geo.cache.CacheInvalidationMessage;
import com.caopan.platform.geo.cache.GeoCacheKeyType;
import com.caopan.platform.geo.cache.GeoCacheKeys;
import com.caopan.platform.geo.cache.TieredCache;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端地理数据缓存清理（L2 Redis + 本机/集群 L1）。
 */
@Service
public class GeoCacheAdminService {

    private static final Logger log = LoggerFactory.getLogger(GeoCacheAdminService.class);

    private final TieredCache tieredCache;
    private final CacheInvalidationBroadcaster broadcaster;

    public GeoCacheAdminService(TieredCache tieredCache, CacheInvalidationBroadcaster broadcaster) {
        this.tieredCache = tieredCache;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void bindBroadcastHandler() {
        broadcaster.setOnRemoteInvalidate(this::applyLocalFromBroadcast);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", broadcaster.instanceId());
        m.put("redisEnabled", tieredCache.redisEnabled());
        m.put("broadcastAvailable", broadcaster.isBroadcastAvailable());
        m.put("localEstimatedSize", tieredCache.localEstimatedSize());
        m.put("supportedScopes", List.of(
                CacheInvalidationMessage.SCOPE_ALL,
                CacheInvalidationMessage.SCOPE_COUNTRY,
                CacheInvalidationMessage.SCOPE_REGION,
                CacheInvalidationMessage.SCOPE_KEYS));
        m.put("dataPatterns", GeoCacheKeys.DATA_CLEAR_PATTERNS);
        m.put("keyTypes", GeoCacheKeys.catalog());
        return m;
    }

    /** 查询全部缓存业务变量名称与 key 模板。 */
    public List<Map<String, Object>> listKeyTypes() {
        return GeoCacheKeys.catalog();
    }

    /** 按业务枚举 + 参数拼装完整 key。 */
    public String buildKey(String typeCode, Map<String, String> params) {
        GeoCacheKeyType type = GeoCacheKeyType.fromCode(typeCode);
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        try {
            return type.buildKey(params);
        } catch (IllegalArgumentException | NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    public ClearResult clear(ClearRequest req, String operator) {
        if (req == null || !StringUtils.hasText(req.scope())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String scope = req.scope().trim().toUpperCase();
        boolean dryRun = Boolean.TRUE.equals(req.dryRun());
        long start = System.currentTimeMillis();

        List<String> patterns = new ArrayList<>();
        List<String> exactKeys = new ArrayList<>();
        switch (scope) {
            case CacheInvalidationMessage.SCOPE_ALL -> patterns.addAll(GeoCacheKeys.DATA_CLEAR_PATTERNS);
            case CacheInvalidationMessage.SCOPE_COUNTRY -> {
                if (!StringUtils.hasText(req.countryCode()) || req.countryCode().trim().length() != 2) {
                    throw new BizException(ErrorCode.PARAM_INVALID);
                }
                String cc = req.countryCode().trim().toUpperCase();
                patterns.add(GeoCacheKeys.treeCountryPattern(cc));
                patterns.add(GeoCacheKeys.PATTERN_COUNTRIES);
            }
            case CacheInvalidationMessage.SCOPE_REGION -> {
                if (req.regionId() == null || req.regionId() <= 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID);
                }
                exactKeys.addAll(buildRegionKeys(req.regionId(), req.parentId(), req.countryCode()));
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID);
        }

        long matchedOrDeleted;
        if (!exactKeys.isEmpty()) {
            matchedOrDeleted = dryRun
                    ? exactKeys.stream().filter(GeoCacheKeys::isGeoDataKey).count()
                    : tieredCache.deleteRedisKeysExact(exactKeys, false);
            if (!dryRun) {
                tieredCache.invalidateLocal(exactKeys);
            }
        } else {
            if (dryRun) {
                matchedOrDeleted = tieredCache.scanRedisKeys(patterns).size();
            } else {
                matchedOrDeleted = tieredCache.clearRedisByPatterns(patterns, false);
                if (CacheInvalidationMessage.SCOPE_ALL.equals(scope)) {
                    tieredCache.invalidateLocalAll();
                } else {
                    // COUNTRY：本机无法按前缀枚举 L1，直接全清 L1（仅 geo 缓存）
                    tieredCache.invalidateLocalAll();
                }
            }
        }

        boolean broadcast = false;
        if (!dryRun) {
            CacheInvalidationMessage msg = new CacheInvalidationMessage(
                    broadcaster.instanceId(),
                    scope,
                    req.countryCode() == null ? null : req.countryCode().trim().toUpperCase(),
                    req.regionId(),
                    req.parentId(),
                    exactKeys.isEmpty() ? null : List.copyOf(exactKeys),
                    System.currentTimeMillis());
            broadcaster.publish(msg);
            broadcast = broadcaster.isBroadcastAvailable();
            log.info("geo cache cleared, scope={}, deletedOrMatched={}, operator={}, broadcast={}",
                    scope, matchedOrDeleted, operator, broadcast);
        }

        return new ClearResult(
                scope,
                dryRun,
                matchedOrDeleted,
                !dryRun,
                broadcast,
                broadcaster.instanceId(),
                System.currentTimeMillis() - start);
    }

    public ClearResult evictKeys(List<String> keys, boolean dryRun, String operator) {
        if (keys == null || keys.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        List<String> safe = keys.stream().filter(GeoCacheKeys::isGeoDataKey).distinct().toList();
        if (safe.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        long start = System.currentTimeMillis();
        long n = tieredCache.deleteRedisKeysExact(safe, dryRun);
        if (!dryRun) {
            tieredCache.invalidateLocal(safe);
            broadcaster.publish(new CacheInvalidationMessage(
                    broadcaster.instanceId(),
                    CacheInvalidationMessage.SCOPE_KEYS,
                    null, null, null,
                    safe,
                    System.currentTimeMillis()));
            log.info("geo cache keys evicted, count={}, operator={}", n, operator);
        }
        return new ClearResult(
                CacheInvalidationMessage.SCOPE_KEYS,
                dryRun,
                n,
                !dryRun,
                !dryRun && broadcaster.isBroadcastAvailable(),
                broadcaster.instanceId(),
                System.currentTimeMillis() - start);
    }

    private void applyLocalFromBroadcast(CacheInvalidationMessage msg) {
        if (msg == null || !StringUtils.hasText(msg.scope())) {
            return;
        }
        try {
            switch (msg.scope().toUpperCase()) {
                case CacheInvalidationMessage.SCOPE_ALL, CacheInvalidationMessage.SCOPE_COUNTRY ->
                        tieredCache.invalidateLocalAll();
                case CacheInvalidationMessage.SCOPE_REGION, CacheInvalidationMessage.SCOPE_KEYS -> {
                    if (msg.keys() != null && !msg.keys().isEmpty()) {
                        tieredCache.invalidateLocal(msg.keys());
                    } else if (msg.regionId() != null) {
                        tieredCache.invalidateLocal(
                                buildRegionKeys(msg.regionId(), msg.parentId(), msg.countryCode()));
                    }
                }
                default -> log.warn("unknown cache invalidate scope={}", msg.scope());
            }
            log.info("applied remote L1 invalidate, scope={}, from={}", msg.scope(), msg.fromInstanceId());
        } catch (Exception e) {
            log.warn("apply remote L1 invalidate failed: {}", e.getMessage());
        }
    }

    private static List<String> buildRegionKeys(Long regionId, Long parentId, String countryCode) {
        Set<String> keys = new LinkedHashSet<>();
        if (regionId != null && regionId > 0) {
            keys.add(GeoCacheKeys.region(regionId));
            keys.add(GeoCacheKeys.path(regionId));
        }
        if (parentId != null && parentId > 0) {
            keys.add(GeoCacheKeys.children(parentId));
            keys.add(GeoCacheKeys.region(parentId));
        }
        keys.add(GeoCacheKeys.countries(null));
        keys.add(GeoCacheKeys.countries(""));
        if (StringUtils.hasText(countryCode) && countryCode.trim().length() == 2) {
            String cc = countryCode.trim().toUpperCase();
            for (int depth = 1; depth <= 5; depth++) {
                keys.add(GeoCacheKeys.tree(cc, 0L, depth));
                if (regionId != null && regionId > 0) {
                    keys.add(GeoCacheKeys.tree(cc, regionId, depth));
                }
                if (parentId != null && parentId > 0) {
                    keys.add(GeoCacheKeys.tree(cc, parentId, depth));
                }
            }
        }
        return List.copyOf(keys);
    }

    public record ClearRequest(
            String scope,
            String countryCode,
            Long regionId,
            Long parentId,
            Boolean dryRun
    ) {
    }

    public record ClearResult(
            String scope,
            boolean dryRun,
            long deletedRedisKeys,
            boolean localL1Cleared,
            boolean broadcast,
            String instanceId,
            long elapsedMs
    ) {
    }
}

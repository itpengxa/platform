package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.support.AdminOperatorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 地理数据缓存管理 API（L1 + L2 + 集群广播）。
 */
@RestController
@RequestMapping("/admin/geo/v1/cache")
public class GeoCacheAdminController {

    private final GeoCacheAdminService cacheAdminService;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public GeoCacheAdminController(
            GeoCacheAdminService cacheAdminService,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.cacheAdminService = cacheAdminService;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(cacheAdminService.stats());
    }

    @GetMapping("/key-types")
    public Result<List<Map<String, Object>>> keyTypes() {
        return Result.ok(cacheAdminService.listKeyTypes());
    }

    @PostMapping("/build-key")
    public Result<Map<String, String>> buildKey(@RequestBody BuildKeyRequest body) {
        String key = cacheAdminService.buildKey(
                body == null ? null : body.type(),
                body == null ? null : body.params());
        return Result.ok(Map.of("key", key));
    }

    @GetMapping("/query")
    public Result<Map<String, Object>> queryGet(@RequestParam(required = false) String key) {
        return Result.ok(cacheAdminService.inspectKey(key, null, null));
    }

    @PostMapping("/query")
    public Result<Map<String, Object>> queryPost(@RequestBody(required = false) QueryRequest body) {
        return Result.ok(cacheAdminService.inspectKey(
                body == null ? null : body.key(),
                body == null ? null : body.type(),
                body == null ? null : body.params()));
    }

    @PostMapping("/clear")
    public Result<GeoCacheAdminService.ClearResult> clear(
            @RequestBody GeoCacheAdminService.ClearRequest body,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        boolean dryRun = body != null && Boolean.TRUE.equals(body.dryRun());
        try {
            GeoCacheAdminService.ClearResult result = cacheAdminService.clear(body, op.operator());
            int cost = (int) (System.currentTimeMillis() - start);
            if (!dryRun) {
                operationLogService.record(RecordRequest.ok(
                        "cache", "CLEAR", "geo_cache",
                        body == null ? null : body.scope(),
                        "scope=" + (body == null ? null : body.scope())
                                + ", country=" + (body == null ? null : body.countryCode())
                                + ", regionId=" + (body == null ? null : body.regionId())
                                + ", deleted=" + result.deletedRedisKeys()
                                + ", broadcast=" + result.broadcast(),
                        null, null,
                        op.operator(), op.operatorId(), op.clientIp(), cost));
            }
            return Result.ok(result);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            if (!dryRun) {
                operationLogService.record(RecordRequest.fail(
                        "cache", "CLEAR", "geo_cache",
                        body == null ? null : body.scope(),
                        "scope=" + (body == null ? null : body.scope()),
                        e.getMessage(),
                        op.operator(), op.operatorId(), op.clientIp(), cost));
            }
            throw e;
        }
    }

    @PostMapping("/evict")
    public Result<GeoCacheAdminService.ClearResult> evict(
            @RequestBody EvictRequest body,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        boolean dryRun = body != null && Boolean.TRUE.equals(body.dryRun());
        List<String> keys = body == null ? null : body.keys();
        String keySummary = keys == null ? "" : keys.stream().limit(20).collect(Collectors.joining(","));
        if (keys != null && keys.size() > 20) {
            keySummary = keySummary + ",...(" + keys.size() + ")";
        }
        try {
            GeoCacheAdminService.ClearResult result =
                    cacheAdminService.evictKeys(keys, dryRun, op.operator());
            int cost = (int) (System.currentTimeMillis() - start);
            if (!dryRun) {
                operationLogService.record(RecordRequest.ok(
                        "cache", "EVICT", "geo_cache",
                        keys != null && keys.size() == 1 ? keys.get(0) : ("count=" + (keys == null ? 0 : keys.size())),
                        "keys=[" + keySummary + "], deleted=" + result.deletedRedisKeys(),
                        null, null,
                        op.operator(), op.operatorId(), op.clientIp(), cost));
            }
            return Result.ok(result);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            if (!dryRun) {
                operationLogService.record(RecordRequest.fail(
                        "cache", "EVICT", "geo_cache", null,
                        "keys=[" + keySummary + "]", e.getMessage(),
                        op.operator(), op.operatorId(), op.clientIp(), cost));
            }
            throw e;
        }
    }

    public record EvictRequest(List<String> keys, Boolean dryRun) {
    }

    public record BuildKeyRequest(String type, Map<String, String> params) {
    }

    public record QueryRequest(String key, String type, Map<String, String> params) {
    }
}

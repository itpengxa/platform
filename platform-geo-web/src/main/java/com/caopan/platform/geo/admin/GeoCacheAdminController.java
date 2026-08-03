package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.admin.access.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 地理数据缓存管理 API（L1 + L2 + 集群广播）。
 */
@RestController
@RequestMapping("/admin/geo/v1/cache")
public class GeoCacheAdminController {

    private final GeoCacheAdminService cacheAdminService;
    private final AdminAuthService adminAuthService;

    public GeoCacheAdminController(GeoCacheAdminService cacheAdminService, AdminAuthService adminAuthService) {
        this.cacheAdminService = cacheAdminService;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(cacheAdminService.stats());
    }

    /** 全部缓存业务变量名与 key 模板（下拉数据源）。 */
    @GetMapping("/key-types")
    public Result<List<Map<String, Object>>> keyTypes() {
        return Result.ok(cacheAdminService.listKeyTypes());
    }

    /** 按业务枚举拼装完整 Redis key。 */
    @PostMapping("/build-key")
    public Result<Map<String, String>> buildKey(@RequestBody BuildKeyRequest body) {
        String key = cacheAdminService.buildKey(
                body == null ? null : body.type(),
                body == null ? null : body.params());
        return Result.ok(Map.of("key", key));
    }

    /** 精确 key 查询缓存内容（不回源）。可传 key，或 type+params。 */
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
        return Result.ok(cacheAdminService.clear(body, resolveOperator(request)));
    }

    @PostMapping("/evict")
    public Result<GeoCacheAdminService.ClearResult> evict(
            @RequestBody EvictRequest body,
            HttpServletRequest request) {
        boolean dryRun = body != null && Boolean.TRUE.equals(body.dryRun());
        List<String> keys = body == null ? null : body.keys();
        return Result.ok(cacheAdminService.evictKeys(keys, dryRun, resolveOperator(request)));
    }

    private String resolveOperator(HttpServletRequest request) {
        String token = AdminAuthController.extractToken(request);
        if (!StringUtils.hasText(token)) {
            return "unknown";
        }
        try {
            AdminSessionCaller caller = adminAuthService.requireSession(token);
            return caller.username();
        } catch (Exception e) {
            return "legacy-secret";
        }
    }

    public record EvictRequest(List<String> keys, Boolean dryRun) {
    }

    public record BuildKeyRequest(String type, Map<String, String> params) {
    }

    public record QueryRequest(String key, String type, Map<String, String> params) {
    }
}

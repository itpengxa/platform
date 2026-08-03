package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.admin.access.AdminAuthService;
import com.caopan.platform.geo.config.runtime.RuntimeConfigService;
import com.caopan.platform.geo.config.runtime.RuntimeConfigService.ConfigItemView;
import com.caopan.platform.geo.config.runtime.RuntimeConfigService.ConfigWriteItem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时配置管理 API（yml 默认 + DB 覆盖 + 热生效）。
 */
@RestController
@RequestMapping("/admin/platform/v1/configs")
public class RuntimeConfigAdminController {

    private final RuntimeConfigService runtimeConfigService;
    private final AdminAuthService adminAuthService;

    public RuntimeConfigAdminController(
            RuntimeConfigService runtimeConfigService,
            AdminAuthService adminAuthService) {
        this.runtimeConfigService = runtimeConfigService;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping
    public Result<Map<String, Object>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groups", runtimeConfigService.listGrouped());
        return Result.ok(data);
    }

    @GetMapping("/{group}")
    public Result<List<ConfigItemView>> listGroup(@PathVariable String group) {
        return Result.ok(runtimeConfigService.listGroup(group));
    }

    @PutMapping
    public Result<Map<String, Object>> save(
            @RequestBody SaveRequest body,
            HttpServletRequest request) {
        List<String> needRestart = runtimeConfigService.save(
                body == null || body.items() == null ? List.of() : body.items(),
                resolveUpdatedBy(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("needRestart", needRestart);
        data.put("groups", runtimeConfigService.listGrouped());
        return Result.ok(data);
    }

    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(
            @RequestBody ResetRequest body,
            HttpServletRequest request) {
        runtimeConfigService.reset(
                body == null || body.keys() == null ? List.of() : body.keys(),
                resolveUpdatedBy(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groups", runtimeConfigService.listGrouped());
        return Result.ok(data);
    }

    @PostMapping("/reload")
    public Result<Void> reload() {
        runtimeConfigService.reloadFromDb();
        return Result.ok(null);
    }

    private String resolveUpdatedBy(HttpServletRequest request) {
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

    public record SaveRequest(List<ConfigWriteItem> items) {
    }

    public record ResetRequest(List<String> keys) {
    }
}

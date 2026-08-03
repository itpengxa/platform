package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.support.AdminOperatorResolver;
import com.caopan.platform.geo.config.runtime.RuntimeConfigService;
import com.caopan.platform.geo.config.runtime.RuntimeConfigService.ConfigItemView;
import com.caopan.platform.geo.config.runtime.RuntimeConfigService.ConfigWriteItem;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.stream.Collectors;

/**
 * 运行时配置管理 API（yml 默认 + DB 覆盖 + 热生效）。
 */
@RestController
@RequestMapping("/admin/platform/v1/configs")
public class RuntimeConfigAdminController {

    private final RuntimeConfigService runtimeConfigService;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public RuntimeConfigAdminController(
            RuntimeConfigService runtimeConfigService,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.runtimeConfigService = runtimeConfigService;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
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
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        List<ConfigWriteItem> items = body == null || body.items() == null ? List.of() : body.items();
        String keys = items.stream()
                .filter(i -> i != null && i.key() != null)
                .map(ConfigWriteItem::key)
                .collect(Collectors.joining(","));
        try {
            List<String> needRestart = runtimeConfigService.save(items, op.operator());
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "config", "UPSERT", "platform_runtime_config",
                    keys.length() > 120 ? keys.substring(0, 120) : keys,
                    "keys=[" + keys + "], count=" + items.size()
                            + (needRestart.isEmpty() ? "" : ", needRestart=" + needRestart),
                    null, null,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("needRestart", needRestart);
            data.put("groups", runtimeConfigService.listGrouped());
            return Result.ok(data);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "config", "UPSERT", "platform_runtime_config",
                    keys.length() > 120 ? keys.substring(0, 120) : keys,
                    "keys=[" + keys + "]", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PostMapping("/create")
    public Result<Map<String, Object>> create(
            @RequestBody RuntimeConfigService.CreateConfigRequest body,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        String key = body == null ? null : body.key();
        try {
            runtimeConfigService.createCustom(body, op.operator());
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "config", "CREATE", "platform_runtime_config", key,
                    "key=" + key + ", group=" + (body == null ? null : body.group())
                            + ", type=" + (body == null ? null : body.valueType())
                            + (body != null && Boolean.TRUE.equals(body.secret()) ? ", secret=***" : ""),
                    null, null,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("groups", runtimeConfigService.listGrouped());
            return Result.ok(data);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "config", "CREATE", "platform_runtime_config", key,
                    "key=" + key, e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(
            @RequestBody ResetRequest body,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        List<String> keys = body == null || body.keys() == null ? List.of() : body.keys();
        String keyJoined = String.join(",", keys);
        try {
            runtimeConfigService.reset(keys, op.operator());
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "config", "RESET", "platform_runtime_config",
                    keyJoined.length() > 120 ? keyJoined.substring(0, 120) : keyJoined,
                    "keys=[" + keyJoined + "]",
                    null, null,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("groups", runtimeConfigService.listGrouped());
            return Result.ok(data);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "config", "RESET", "platform_runtime_config",
                    keyJoined.length() > 120 ? keyJoined.substring(0, 120) : keyJoined,
                    "keys=[" + keyJoined + "]", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PostMapping("/reload")
    public Result<Void> reload(HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            runtimeConfigService.reloadFromDb();
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "config", "RELOAD", "platform_runtime_config", null,
                    "reloadFromDb", null, null,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "config", "RELOAD", "platform_runtime_config", null,
                    "reloadFromDb", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    public record SaveRequest(List<ConfigWriteItem> items) {
    }

    public record ResetRequest(List<String> keys) {
    }
}

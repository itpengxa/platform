package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.access.AccessTokenAdminService;
import com.caopan.platform.geo.admin.access.AccessTokenAdminService.AccessTokenVO;
import com.caopan.platform.geo.admin.support.AdminOperatorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Token 管理 API（GEO-002）。
 */
@RestController
@RequestMapping("/admin/platform/v1/tokens")
public class AccessTokenAdminController {

    private final AccessTokenAdminService service;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public AccessTokenAdminController(
            AccessTokenAdminService service,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.service = service;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
    }

    @GetMapping("/page")
    public Result<PageResult<AccessTokenVO>> page(
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(service.page(clientCode, status, pageNum, pageSize));
    }

    @PostMapping("/issue")
    public Result<Map<String, String>> issue(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String clientCode = body == null ? null : body.get("clientCode");
        String clientName = body == null ? null : body.get("clientName");
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            Map<String, String> issued = service.issue(clientCode, clientName);
            int cost = (int) (System.currentTimeMillis() - start);
            String prefix = issued == null ? null : issued.get("tokenPrefix");
            operationLogService.record(RecordRequest.ok(
                    "token", "ISSUE", "platform_access_token",
                    issued == null ? null : issued.get("clientCode"),
                    "clientCode=" + clientCode + ", tokenPrefix=" + prefix,
                    null,
                    "clientCode=" + (issued == null ? null : issued.get("clientCode"))
                            + ", tokenPrefix=" + prefix,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(issued);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "token", "ISSUE", "platform_access_token", clientCode,
                    "clientCode=" + clientCode, e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id, HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            service.revoke(id);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "token", "REVOKE", "platform_access_token", String.valueOf(id),
                    "revoke token id=" + id,
                    null, "status=0",
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "token", "REVOKE", "platform_access_token", String.valueOf(id),
                    "revoke", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }
}

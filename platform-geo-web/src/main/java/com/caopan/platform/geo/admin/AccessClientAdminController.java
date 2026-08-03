package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.access.AccessClientAdminService;
import com.caopan.platform.geo.admin.access.AccessClientAdminService.AccessClientCreateRequest;
import com.caopan.platform.geo.admin.access.AccessClientAdminService.AccessClientUpdateRequest;
import com.caopan.platform.geo.admin.access.AccessClientAdminService.AccessClientVO;
import com.caopan.platform.geo.admin.support.AdminOperatorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 接入方白名单管理 API（GEO-002）。
 */
@RestController
@RequestMapping("/admin/platform/v1/clients")
public class AccessClientAdminController {

    private final AccessClientAdminService service;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public AccessClientAdminController(
            AccessClientAdminService service,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.service = service;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
    }

    @GetMapping("/page")
    public Result<PageResult<AccessClientVO>> page(
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer allowIssue,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(service.page(clientCode, status, allowIssue, pageNum, pageSize));
    }

    @PostMapping
    public Result<AccessClientVO> create(@RequestBody AccessClientCreateRequest req, HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            AccessClientVO vo = service.create(req);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "client", "CREATE", "platform_access_client",
                    vo == null || vo.id() == null ? null : String.valueOf(vo.id()),
                    "clientCode=" + (req == null ? null : req.clientCode())
                            + ", allowIssue=" + (req == null ? null : req.allowIssue()),
                    null,
                    vo == null ? null : ("id=" + vo.id() + ", clientCode=" + vo.clientCode()
                            + ", status=" + vo.status() + ", allowIssue=" + vo.allowIssue()),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(vo);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "client", "CREATE", "platform_access_client", null,
                    "clientCode=" + (req == null ? null : req.clientCode()),
                    e.getMessage(), op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PutMapping("/{id}")
    public Result<Void> update(
            @PathVariable Long id,
            @RequestBody AccessClientUpdateRequest req,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            service.update(id, req);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "client", "UPDATE", "platform_access_client", String.valueOf(id),
                    "clientName=" + (req == null ? null : req.clientName())
                            + ", allowIssue=" + (req == null ? null : req.allowIssue()),
                    null, null,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "client", "UPDATE", "platform_access_client", String.valueOf(id),
                    "update", e.getMessage(), op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PatchMapping("/{id}/status")
    public Result<Void> patchStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> body,
            HttpServletRequest request) {
        Integer status = body == null ? null : body.get("status");
        if (status == null) {
            status = body == null ? null : body.get("value");
        }
        int resolved = status == null ? 0 : status;
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            service.patchStatus(id, resolved);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "client", "STATUS", "platform_access_client", String.valueOf(id),
                    "status -> " + resolved,
                    null, "status=" + resolved,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "client", "STATUS", "platform_access_client", String.valueOf(id),
                    "status -> " + resolved, e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }
}

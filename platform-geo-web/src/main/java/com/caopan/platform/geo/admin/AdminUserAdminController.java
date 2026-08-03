package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.access.AdminUserAdminService;
import com.caopan.platform.geo.admin.access.AdminUserAdminService.AdminUserVO;
import com.caopan.platform.geo.admin.access.AdminUserAdminService.CreateRequest;
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
 * 管理员用户维护 API。
 */
@RestController
@RequestMapping("/admin/platform/v1/admins")
public class AdminUserAdminController {

    private final AdminUserAdminService adminUserAdminService;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public AdminUserAdminController(
            AdminUserAdminService adminUserAdminService,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.adminUserAdminService = adminUserAdminService;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
    }

    @GetMapping("/page")
    public Result<PageResult<AdminUserVO>> page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(adminUserAdminService.page(username, status, pageNum, pageSize));
    }

    @PostMapping
    public Result<AdminUserVO> create(@RequestBody CreateRequest req, HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            AdminUserVO vo = adminUserAdminService.create(req);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "admin", "CREATE", "platform_admin_user",
                    vo == null || vo.id() == null ? null : String.valueOf(vo.id()),
                    "username=" + (req == null ? null : req.username()),
                    null,
                    vo == null ? null : ("id=" + vo.id() + ", username=" + vo.username()
                            + ", status=" + vo.status()),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(vo);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "admin", "CREATE", "platform_admin_user", null,
                    "username=" + (req == null ? null : req.username()),
                    e.getMessage(), op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String pwd = body == null ? null : body.get("password");
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            adminUserAdminService.resetPassword(id, pwd);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "admin", "RESET_PASSWORD", "platform_admin_user", String.valueOf(id),
                    "reset password (value redacted)",
                    null, null,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "admin", "RESET_PASSWORD", "platform_admin_user", String.valueOf(id),
                    "reset password", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PatchMapping("/{id}/status")
    public Result<Void> patchStatus(
            @PathVariable Long id,
            @RequestParam(required = false) Integer status,
            @RequestBody(required = false) Map<String, Integer> body,
            HttpServletRequest request) {
        Integer resolved = status;
        if (resolved == null && body != null) {
            resolved = body.get("status");
        }
        int target = resolved == null ? 0 : resolved;
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            adminUserAdminService.patchStatus(id, target);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "admin", "STATUS", "platform_admin_user", String.valueOf(id),
                    "status -> " + target,
                    null, "status=" + target,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "admin", "STATUS", "platform_admin_user", String.valueOf(id),
                    "status -> " + target, e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }
}

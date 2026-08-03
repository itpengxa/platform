package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.access.AdminUserAdminService;
import com.caopan.platform.geo.admin.access.AdminUserAdminService.AdminUserVO;
import com.caopan.platform.geo.admin.access.AdminUserAdminService.CreateRequest;
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

    public AdminUserAdminController(AdminUserAdminService adminUserAdminService) {
        this.adminUserAdminService = adminUserAdminService;
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
    public Result<AdminUserVO> create(@RequestBody CreateRequest req) {
        return Result.ok(adminUserAdminService.create(req));
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String pwd = body == null ? null : body.get("password");
        adminUserAdminService.resetPassword(id, pwd);
        return Result.ok(null);
    }

    @PatchMapping("/{id}/status")
    public Result<Void> patchStatus(
            @PathVariable Long id,
            @RequestParam(required = false) Integer status,
            @RequestBody(required = false) Map<String, Integer> body) {
        Integer resolved = status;
        if (resolved == null && body != null) {
            resolved = body.get("status");
        }
        adminUserAdminService.patchStatus(id, resolved == null ? 0 : resolved);
        return Result.ok(null);
    }
}

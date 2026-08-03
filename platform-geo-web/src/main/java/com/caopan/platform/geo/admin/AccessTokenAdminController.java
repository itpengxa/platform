package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.access.AccessTokenAdminService;
import com.caopan.platform.geo.admin.access.AccessTokenAdminService.AccessTokenVO;
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

    public AccessTokenAdminController(AccessTokenAdminService service) {
        this.service = service;
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
    public Result<Map<String, String>> issue(@RequestBody Map<String, String> body) {
        String clientCode = body == null ? null : body.get("clientCode");
        String clientName = body == null ? null : body.get("clientName");
        return Result.ok(service.issue(clientCode, clientName));
    }

    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id) {
        service.revoke(id);
        return Result.ok(null);
    }
}

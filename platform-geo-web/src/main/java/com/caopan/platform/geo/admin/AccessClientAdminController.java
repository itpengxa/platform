package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.access.AccessClientAdminService;
import com.caopan.platform.geo.admin.access.AccessClientAdminService.AccessClientCreateRequest;
import com.caopan.platform.geo.admin.access.AccessClientAdminService.AccessClientUpdateRequest;
import com.caopan.platform.geo.admin.access.AccessClientAdminService.AccessClientVO;
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

    public AccessClientAdminController(AccessClientAdminService service) {
        this.service = service;
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
    public Result<AccessClientVO> create(@RequestBody AccessClientCreateRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody AccessClientUpdateRequest req) {
        service.update(id, req);
        return Result.ok(null);
    }

    @PatchMapping("/{id}/status")
    public Result<Void> patchStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer status = body == null ? null : body.get("status");
        if (status == null) {
            status = body == null ? null : body.get("value");
        }
        service.patchStatus(id, status == null ? 0 : status);
        return Result.ok(null);
    }
}

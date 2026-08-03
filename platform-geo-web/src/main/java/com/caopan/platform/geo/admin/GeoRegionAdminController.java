package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.GeoAdminService.GeoRegionAdminVO;
import com.caopan.platform.geo.admin.GeoAdminService.GeoRegionCreateRequest;
import com.caopan.platform.geo.admin.GeoAdminService.GeoRegionUpdateRequest;
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
 * 区划主表管理 API（GEO-002）。
 */
@RestController
@RequestMapping("/admin/geo/v1/regions")
public class GeoRegionAdminController {

    private final GeoAdminService geoAdminService;

    public GeoRegionAdminController(GeoAdminService geoAdminService) {
        this.geoAdminService = geoAdminService;
    }

    @GetMapping("/page")
    public Result<PageResult<GeoRegionAdminVO>> page(
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(geoAdminService.page(countryCode, parentId, level, keyword, status, source, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public Result<GeoRegionAdminVO> detail(@PathVariable Long id) {
        return Result.ok(geoAdminService.detail(id));
    }

    @PostMapping
    public Result<GeoRegionAdminVO> create(@RequestBody GeoRegionCreateRequest req) {
        return Result.ok(geoAdminService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody GeoRegionUpdateRequest req) {
        geoAdminService.update(id, req);
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
        if (resolved == null) {
            resolved = 0;
        }
        geoAdminService.patchStatus(id, resolved);
        return Result.ok(null);
    }
}

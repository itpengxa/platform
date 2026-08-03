package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.GeoAdminService.GeoRegionAdminVO;
import com.caopan.platform.geo.admin.GeoAdminService.GeoRegionCreateRequest;
import com.caopan.platform.geo.admin.GeoAdminService.GeoRegionUpdateRequest;
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
 * 区划主表管理 API（GEO-002）。
 */
@RestController
@RequestMapping("/admin/geo/v1/regions")
public class GeoRegionAdminController {

    private final GeoAdminService geoAdminService;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public GeoRegionAdminController(
            GeoAdminService geoAdminService,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.geoAdminService = geoAdminService;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
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
    public Result<GeoRegionAdminVO> create(
            @RequestBody GeoRegionCreateRequest req,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            GeoRegionAdminVO vo = geoAdminService.create(req);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "region", "CREATE", "geo_region",
                    vo == null || vo.id() == null ? null : String.valueOf(vo.id()),
                    "parentId=" + (req == null ? null : req.parentId())
                            + ", name=" + (req == null ? null : req.name())
                            + ", status=" + (req == null ? null : req.status()),
                    null,
                    vo == null ? null : ("id=" + vo.id() + ", name=" + vo.name()
                            + ", parentId=" + vo.parentId() + ", status=" + vo.status()),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(vo);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "region", "CREATE", "geo_region", null,
                    "parentId=" + (req == null ? null : req.parentId()) + ", name=" + (req == null ? null : req.name()),
                    e.getMessage(), op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PutMapping("/{id}")
    public Result<Void> update(
            @PathVariable Long id,
            @RequestBody GeoRegionUpdateRequest req,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        GeoRegionAdminVO before = null;
        try {
            before = geoAdminService.detail(id);
        } catch (Exception ignored) {
            // detail miss will fail again in update
        }
        try {
            geoAdminService.update(id, req);
            GeoRegionAdminVO after = geoAdminService.detail(id);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "region", "UPDATE", "geo_region", String.valueOf(id),
                    "name=" + (req == null ? null : req.name())
                            + ", status=" + (req == null ? null : req.status()),
                    snapshot(before), snapshot(after),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "region", "UPDATE", "geo_region", String.valueOf(id),
                    "update", e.getMessage(), op.operator(), op.operatorId(), op.clientIp(), cost));
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
        if (resolved == null) {
            resolved = 0;
        }
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        Integer oldStatus = null;
        try {
            GeoRegionAdminVO before = geoAdminService.detail(id);
            oldStatus = before == null ? null : before.status();
        } catch (Exception ignored) {
            // ignore
        }
        try {
            geoAdminService.patchStatus(id, resolved);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "region", "STATUS", "geo_region", String.valueOf(id),
                    "status " + oldStatus + " -> " + resolved,
                    oldStatus == null ? null : ("status=" + oldStatus),
                    "status=" + resolved,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "region", "STATUS", "geo_region", String.valueOf(id),
                    "status -> " + resolved, e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    private static String snapshot(GeoRegionAdminVO vo) {
        if (vo == null) {
            return null;
        }
        return "id=" + vo.id()
                + ", name=" + vo.name()
                + ", parentId=" + vo.parentId()
                + ", status=" + vo.status()
                + ", countryCode=" + vo.countryCode();
    }
}

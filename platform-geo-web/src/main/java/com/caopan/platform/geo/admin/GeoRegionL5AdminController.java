package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.GeoRegionL5AdminService.GeoRegionL5AdminVO;
import com.caopan.platform.geo.admin.GeoRegionL5AdminService.GeoRegionL5CreateRequest;
import com.caopan.platform.geo.admin.GeoRegionL5AdminService.GeoRegionL5UpdateRequest;
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
 * L5 街/镇管理 API（表 geo_region_l5）。
 */
@RestController
@RequestMapping("/admin/geo/v1/regions-l5")
public class GeoRegionL5AdminController {

    private final GeoRegionL5AdminService geoRegionL5AdminService;
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public GeoRegionL5AdminController(
            GeoRegionL5AdminService geoRegionL5AdminService,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.geoRegionL5AdminService = geoRegionL5AdminService;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
    }

    @GetMapping("/page")
    public Result<PageResult<GeoRegionL5AdminVO>> page(
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(geoRegionL5AdminService.page(
                countryCode, parentId, keyword, status, source, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public Result<GeoRegionL5AdminVO> detail(@PathVariable Long id) {
        return Result.ok(geoRegionL5AdminService.detail(id));
    }

    @PostMapping
    public Result<GeoRegionL5AdminVO> create(
            @RequestBody GeoRegionL5CreateRequest req,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            GeoRegionL5AdminVO vo = geoRegionL5AdminService.create(req);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "region_l5", "CREATE", "geo_region_l5",
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
                    "region_l5", "CREATE", "geo_region_l5", null,
                    "parentId=" + (req == null ? null : req.parentId()) + ", name=" + (req == null ? null : req.name()),
                    e.getMessage(), op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PutMapping("/{id}")
    public Result<Void> update(
            @PathVariable Long id,
            @RequestBody GeoRegionL5UpdateRequest req,
            HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        GeoRegionL5AdminVO before = null;
        try {
            before = geoRegionL5AdminService.detail(id);
        } catch (Exception ignored) {
            // detail miss will fail again in update
        }
        try {
            geoRegionL5AdminService.update(id, req);
            GeoRegionL5AdminVO after = geoRegionL5AdminService.detail(id);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "region_l5", "UPDATE", "geo_region_l5", String.valueOf(id),
                    "name=" + (req == null ? null : req.name())
                            + ", status=" + (req == null ? null : req.status()),
                    snapshot(before), snapshot(after),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "region_l5", "UPDATE", "geo_region_l5", String.valueOf(id),
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
            GeoRegionL5AdminVO before = geoRegionL5AdminService.detail(id);
            oldStatus = before == null ? null : before.status();
        } catch (Exception ignored) {
            // ignore
        }
        try {
            geoRegionL5AdminService.patchStatus(id, resolved);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "region_l5", "STATUS", "geo_region_l5", String.valueOf(id),
                    "status " + oldStatus + " -> " + resolved,
                    oldStatus == null ? null : ("status=" + oldStatus),
                    "status=" + resolved,
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "region_l5", "STATUS", "geo_region_l5", String.valueOf(id),
                    "status -> " + resolved, e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    private static String snapshot(GeoRegionL5AdminVO vo) {
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

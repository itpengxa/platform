package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.entity.GeoRegionReport;
import com.caopan.platform.geo.report.GeoReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 上报记录管理 API（GEO-002）。
 */
@RestController
@RequestMapping("/admin/geo/v1/reports")
public class GeoReportAdminController {

    private final GeoReportService geoReportService;

    public GeoReportAdminController(GeoReportService geoReportService) {
        this.geoReportService = geoReportService;
    }

    @GetMapping("/page")
    public Result<PageResult<GeoRegionReport>> page(
            @RequestParam(required = false) String resultStatus,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(geoReportService.pageAdmin(resultStatus, countryCode, clientCode,
                from, to, pageNum, pageSize));
    }

    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        geoReportService.approve(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id) {
        geoReportService.reject(id);
        return Result.ok(null);
    }
}

package com.caopan.platform.geo.controller;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.report.GeoReportService;
import com.caopan.platform.geo.report.GeoReportService.ReportMissingRequest;
import com.caopan.platform.geo.report.GeoReportService.ReportResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧缺省上报 API（GEO-002，走 Token 切面）。
 */
@RestController
@RequestMapping("/api/geo/v1/report")
public class GeoReportController {

    private final GeoReportService geoReportService;

    public GeoReportController(GeoReportService geoReportService) {
        this.geoReportService = geoReportService;
    }

    @PostMapping("/missing")
    public Result<ReportResponse> reportMissing(@RequestBody ReportMissingRequest req) {
        return Result.ok(geoReportService.reportMissing(req));
    }
}

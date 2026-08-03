package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.AdminOperationLogService.RecordRequest;
import com.caopan.platform.geo.admin.support.AdminOperatorResolver;
import com.caopan.platform.geo.entity.GeoRegionReport;
import com.caopan.platform.geo.report.GeoReportService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AdminOperationLogService operationLogService;
    private final AdminOperatorResolver operatorResolver;

    public GeoReportAdminController(
            GeoReportService geoReportService,
            AdminOperationLogService operationLogService,
            AdminOperatorResolver operatorResolver) {
        this.geoReportService = geoReportService;
        this.operationLogService = operationLogService;
        this.operatorResolver = operatorResolver;
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
    public Result<Void> approve(@PathVariable Long id, HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            geoReportService.approve(id);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "report", "APPROVE", "geo_region_report", String.valueOf(id),
                    "approve report id=" + id,
                    null, "resultStatus=MANUAL_CREATED",
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "report", "APPROVE", "geo_region_report", String.valueOf(id),
                    "approve", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }

    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, HttpServletRequest request) {
        AdminOperatorResolver.Resolved op = operatorResolver.resolve(request);
        long start = System.currentTimeMillis();
        try {
            geoReportService.reject(id);
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.ok(
                    "report", "REJECT", "geo_region_report", String.valueOf(id),
                    "reject report id=" + id,
                    null, "resultStatus=REJECTED",
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            return Result.ok(null);
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            operationLogService.record(RecordRequest.fail(
                    "report", "REJECT", "geo_region_report", String.valueOf(id),
                    "reject", e.getMessage(),
                    op.operator(), op.operatorId(), op.clientIp(), cost));
            throw e;
        }
    }
}

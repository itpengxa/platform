package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.admin.StatsAdminService.ClientStatRow;
import com.caopan.platform.geo.admin.StatsAdminService.HotRegionRow;
import com.caopan.platform.geo.admin.StatsAdminService.OverviewStats;
import com.caopan.platform.geo.admin.StatsAdminService.TimelineRow;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据看板 API（GEO-002）。
 */
@RestController
@RequestMapping("/admin/platform/v1/stats")
public class StatsAdminController {

    private final StatsAdminService statsAdminService;

    public StatsAdminController(StatsAdminService statsAdminService) {
        this.statsAdminService = statsAdminService;
    }

    @GetMapping("/overview")
    public Result<OverviewStats> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(statsAdminService.overview(date));
    }

    @GetMapping("/by-client")
    public Result<List<ClientStatRow>> byClient(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "hour") String granularity,
            @RequestParam(required = false) String clientCode) {
        return Result.ok(statsAdminService.byClient(date, granularity, clientCode));
    }

    @GetMapping("/timeline")
    public Result<List<TimelineRow>> timeline(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "hour") String granularity,
            @RequestParam(required = false) String clientCode) {
        return Result.ok(statsAdminService.timeline(date, granularity, clientCode));
    }

    @GetMapping("/hot-regions")
    public Result<List<HotRegionRow>> hotRegions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(statsAdminService.hotRegions(date, countryCode, level, limit));
    }
}

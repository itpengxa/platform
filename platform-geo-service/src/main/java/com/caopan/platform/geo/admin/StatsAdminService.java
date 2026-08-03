package com.caopan.platform.geo.admin;

import com.caopan.platform.geo.mapper.PlatformApiAccessStatMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 数据看板聚合（GEO-002）。
 */
@Service
public class StatsAdminService {

    private final PlatformApiAccessStatMapper statMapper;

    public StatsAdminService(PlatformApiAccessStatMapper statMapper) {
        this.statMapper = statMapper;
    }

    public OverviewStats overview(LocalDate date) {
        TimeRange range = dayRange(date);
        OverviewStats stats = statMapper.selectOverview(range.from(), range.to());
        if (stats == null) {
            return new OverviewStats(0, 0, 0, 0.0);
        }
        long total = stats.totalCalls();
        long success = stats.successCalls();
        double rate = total == 0 ? 0.0 : (success * 100.0 / total);
        return new OverviewStats(total, success, stats.activeClients(), rate);
    }

    public List<ClientStatRow> byClient(LocalDate date, String granularity, String clientCode) {
        TimeRange range = dayRange(date);
        return statMapper.selectByClient(range.from(), range.to(), trim(clientCode), normalizeGranularity(granularity));
    }

    public List<TimelineRow> timeline(LocalDate date, String granularity, String clientCode) {
        TimeRange range = dayRange(date);
        return statMapper.selectTimeline(range.from(), range.to(), trim(clientCode), normalizeGranularity(granularity));
    }

    public List<HotRegionRow> hotRegions(LocalDate date, String countryCode, Integer regionLevel, int limit) {
        TimeRange range = dayRange(date);
        int lim = Math.min(Math.max(limit, 1), 100);
        String cc = StringUtils.hasText(countryCode) ? countryCode.trim().toUpperCase() : null;
        return statMapper.selectHotRegions(range.from(), range.to(), cc, regionLevel, lim);
    }

    private static TimeRange dayRange(LocalDate date) {
        LocalDate d = date == null ? LocalDate.now() : date;
        LocalDateTime from = d.atStartOfDay();
        LocalDateTime to = d.plusDays(1).atStartOfDay();
        return new TimeRange(from, to);
    }

    private static String normalizeGranularity(String g) {
        if (!StringUtils.hasText(g)) {
            return "hour";
        }
        String v = g.trim().toLowerCase();
        return switch (v) {
            case "minute", "day" -> v;
            default -> "hour";
        };
    }

    private static String trim(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    public record OverviewStats(long totalCalls, long successCalls, long activeClients, double successRate) {
    }

    public record ClientStatRow(String clientCode, long callCount, long successCount) {
    }

    public record TimelineRow(String bucket, long callCount, long successCount) {
    }

    public record HotRegionRow(Long regionId, String countryCode, Integer level, String name, long callCount) {
    }
}

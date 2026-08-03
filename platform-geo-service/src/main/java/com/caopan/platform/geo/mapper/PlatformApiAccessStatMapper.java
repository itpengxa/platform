package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.PlatformApiAccessStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * API 调用记录 Mapper（GEO-001 / platform-geo-service）。
 * <p>仅使用 MyBatis-Plus {@code insert} 落库；日聚合由运维 SQL 完成。</p>
 */
@Mapper
public interface PlatformApiAccessStatMapper extends BaseMapper<PlatformApiAccessStat> {

    com.caopan.platform.geo.admin.StatsAdminService.OverviewStats selectOverview(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    java.util.List<com.caopan.platform.geo.admin.StatsAdminService.ClientStatRow> selectByClient(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("clientCode") String clientCode,
            @Param("granularity") String granularity);

    java.util.List<com.caopan.platform.geo.admin.StatsAdminService.TimelineRow> selectTimeline(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("clientCode") String clientCode,
            @Param("granularity") String granularity);

    java.util.List<com.caopan.platform.geo.admin.StatsAdminService.HotRegionRow> selectHotRegions(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("countryCode") String countryCode,
            @Param("regionLevel") Integer regionLevel,
            @Param("limit") int limit);
}

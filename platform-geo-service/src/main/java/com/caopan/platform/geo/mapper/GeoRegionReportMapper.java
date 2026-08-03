package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoRegionReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 区划上报记录 Mapper（GEO-002）。
 */
@Mapper
public interface GeoRegionReportMapper extends BaseMapper<GeoRegionReport> {

    long countAdminPage(@Param("resultStatus") String resultStatus,
                        @Param("countryCode") String countryCode,
                        @Param("clientCode") String clientCode,
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

    List<GeoRegionReport> pageAdmin(@Param("resultStatus") String resultStatus,
                                    @Param("countryCode") String countryCode,
                                    @Param("clientCode") String clientCode,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    long countByParentAndName(@Param("parentId") Long parentId, @Param("missingName") String missingName);

    long countRecentByClient(@Param("clientCode") String clientCode,
                             @Param("from") LocalDateTime from);

    /** 审批/驳回前行锁，避免并发双入主表或状态互相覆盖。 */
    GeoRegionReport selectForUpdate(@Param("id") Long id);

    /**
     * 条件更新状态（CAS）：仅当当前 status 落在 expectedStatuses 时成功。
     *
     * @return 影响行数
     */
    int updateStatusIf(@Param("id") Long id,
                       @Param("fromStatuses") java.util.List<String> fromStatuses,
                       @Param("toStatus") String toStatus,
                       @Param("regionId") Long regionId,
                       @Param("updatedAt") LocalDateTime updatedAt);
}

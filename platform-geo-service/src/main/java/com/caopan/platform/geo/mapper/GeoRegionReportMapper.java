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
}

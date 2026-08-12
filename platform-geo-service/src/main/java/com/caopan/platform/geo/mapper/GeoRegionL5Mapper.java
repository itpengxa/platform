package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoRegionL5;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * L5 街/镇 Mapper（geo_region_l5）。
 */
@Mapper
public interface GeoRegionL5Mapper extends BaseMapper<GeoRegionL5> {

    GeoRegionL5 findByIdAnyStatus(@Param("id") Long id);

    long countAdminPage(@Param("countryCode") String countryCode,
                        @Param("parentId") Long parentId,
                        @Param("keyword") String keyword,
                        @Param("status") Integer status,
                        @Param("source") String source);

    List<GeoRegionL5> pageAdmin(@Param("countryCode") String countryCode,
                                @Param("parentId") Long parentId,
                                @Param("keyword") String keyword,
                                @Param("status") Integer status,
                                @Param("source") String source,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    long countSameNameUnderParent(@Param("parentId") Long parentId,
                                  @Param("name") String name,
                                  @Param("nameEn") String nameEn,
                                  @Param("excludeId") Long excludeId);

    Long nextId();
}

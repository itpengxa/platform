package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoRegion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 2026-07-23 GEO-001 区划 Mapper
 */
@Mapper
public interface GeoRegionMapper extends BaseMapper<GeoRegion> {

    List<GeoRegion> listByParentId(@Param("parentId") Long parentId);

    GeoRegion findEnabledById(@Param("id") Long id);

    GeoRegion findCountryByCode(@Param("countryCode") String countryCode);

    List<GeoRegion> listSubtree(@Param("pathPrefix") String pathPrefix,
                               @Param("maxLevel") Integer maxLevel);

    List<GeoRegion> listByIds(@Param("ids") List<Long> ids);

    List<GeoRegion> search(@Param("keyword") String keyword,
                          @Param("countryCode") String countryCode,
                          @Param("level") Integer level,
                          @Param("limit") Integer limit);

    /**
     * 批量统计子节点数，返回 Map 列表：parentId / cnt
     */
    List<java.util.Map<String, Object>> countChildrenByParentIds(@Param("parentIds") List<Long> parentIds);
}

package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoRegion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 行政区划树 Mapper（GEO-001 / platform-geo-service）。
 * <p>MyBatis-Plus 基础 CRUD + 树形查询。依赖 parent_id + path 索引做高效子级/子树查询，
 * 无需递归 SQL。search 为国家维度下的名称前缀匹配（非模糊）。</p>
 */
@Mapper
public interface GeoRegionMapper extends BaseMapper<GeoRegion> {

    /**
     * 按父节点查询直属启用子区划。
     *
     * @param parentId 父节点 ID
     * @return 子区划列表
     */
    List<GeoRegion> listByParentId(@Param("parentId") Long parentId);

    /**
     * 按主键查询启用状态的区划。
     *
     * @param id 区划 ID
     * @return 区划实体，不存在则 null
     */
    GeoRegion findEnabledById(@Param("id") Long id);

    /**
     * 按 ISO2 国家码查询国家级区划节点。
     *
     * @param countryCode 国家 ISO2 编码
     * @return 国家级区划，不存在则 null
     */
    GeoRegion findCountryByCode(@Param("countryCode") String countryCode);

    /**
     * 按物化路径前缀查询子树节点。
     *
     * @param pathPrefix 路径前缀，如 {@code /1/200000001/}
     * @param maxLevel   最大层级，可空
     * @param maxRows    最大行数（硬限制，防大包）
     * @return 子树节点列表
     */
    List<GeoRegion> listSubtree(@Param("countryCode") String countryCode,
                               @Param("pathPrefix") String pathPrefix,
                               @Param("maxLevel") Integer maxLevel,
                               @Param("maxRows") Integer maxRows);

    /**
     * 按 ID 批量查询区划。
     *
     * @param ids 区划 ID 列表
     * @return 区划列表
     */
    List<GeoRegion> listByIds(@Param("ids") List<Long> ids);

    /**
     * 关键词搜索区划（name/nameEn/nameCh 前缀匹配，须带国家维度）。
     *
     * @param keyword     关键词前缀
     * @param countryCode 国家 ISO2（必填语义由上层保证）
     * @param level       层级过滤，可空
     * @param limit       返回条数上限
     * @return 命中列表
     */
    List<GeoRegion> search(@Param("keyword") String keyword,
                          @Param("countryCode") String countryCode,
                          @Param("level") Integer level,
                          @Param("limit") Integer limit);

    /**
     * 批量统计各父节点下的启用子节点数量。
     *
     * @param parentIds 父节点 ID 列表
     * @return 每行含 parentId、cnt
     */
    List<Map<String, Object>> countChildrenByParentIds(@Param("parentIds") List<Long> parentIds);

    GeoRegion findByIdAnyStatus(@Param("id") Long id);

    long countAdminPage(@Param("countryCode") String countryCode,
                        @Param("parentId") Long parentId,
                        @Param("level") Integer level,
                        @Param("keyword") String keyword,
                        @Param("status") Integer status,
                        @Param("source") String source);

    List<GeoRegion> pageAdmin(@Param("countryCode") String countryCode,
                              @Param("parentId") Long parentId,
                              @Param("level") Integer level,
                              @Param("keyword") String keyword,
                              @Param("status") Integer status,
                              @Param("source") String source,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    long countSameNameUnderParent(@Param("parentId") Long parentId,
                                  @Param("name") String name,
                                  @Param("nameEn") String nameEn,
                                  @Param("excludeId") Long excludeId);

    Long nextIdForLevel(@Param("level") int level);

    /**
     * 经纬度包围盒内启用区划（须带 lat/lng）；用于本库近邻反查。
     *
     * @param minLat      纬度下界
     * @param maxLat      纬度上界
     * @param minLon      经度下界
     * @param maxLon      经度上界
     * @param countryCode 可选 ISO2
     * @param minLevel    可选最小层级（含）
     * @param maxLevel    可选最大层级（含）
     * @param limit       条数上限
     */
    List<GeoRegion> listInBoundingBox(@Param("minLat") double minLat,
                                      @Param("maxLat") double maxLat,
                                      @Param("minLon") double minLon,
                                      @Param("maxLon") double maxLon,
                                      @Param("countryCode") String countryCode,
                                      @Param("minLevel") Integer minLevel,
                                      @Param("maxLevel") Integer maxLevel,
                                      @Param("limit") int limit);

    /**
     * 包围盒内按平面近似距离排序取最近若干条（再由应用层用 Haversine 精排）。
     */
    List<GeoRegion> listNearestInBoundingBox(@Param("lat") double lat,
                                             @Param("lon") double lon,
                                             @Param("minLat") double minLat,
                                             @Param("maxLat") double maxLat,
                                             @Param("minLon") double minLon,
                                             @Param("maxLon") double maxLon,
                                             @Param("countryCode") String countryCode,
                                             @Param("minLevel") Integer minLevel,
                                             @Param("maxLevel") Integer maxLevel,
                                             @Param("limit") int limit);

    /**
     * 基于 location(POINT SRID 4326) + SPATIAL INDEX 的近邻查询。
     * 需先执行 sql/geo_nearest_spatial_006.sql。
     *
     * @param envelopeWkt MBR 多边形 WKT，坐标序 lon lat
     */
    List<GeoRegion> listNearestSpatial(@Param("lat") double lat,
                                       @Param("lon") double lon,
                                       @Param("envelopeWkt") String envelopeWkt,
                                       @Param("maxDistanceM") double maxDistanceM,
                                       @Param("countryCode") String countryCode,
                                       @Param("minLevel") Integer minLevel,
                                       @Param("maxLevel") Integer maxLevel,
                                       @Param("limit") int limit);
}

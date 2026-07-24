package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoRegion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 行政区划树 Mapper。MyBatis-Plus 基础 CRUD + 树形查询。
 * 以 parent_id + path 索引实现高效树查询，无需递归 SQL。
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
     * @param pathPrefix 路径前缀，如 /1/200000001/
     * @param maxLevel   最大层级，可空
     * @param maxRows    最大行数（硬限制）
     * @return 子树节点列表
     */
    List<GeoRegion> listSubtree(@Param("pathPrefix") String pathPrefix,
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
     * 关键词搜索区划（本地名/英文/中文模糊匹配）。
     *
     * @param keyword     关键词
     * @param countryCode 国家过滤，可空
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
}

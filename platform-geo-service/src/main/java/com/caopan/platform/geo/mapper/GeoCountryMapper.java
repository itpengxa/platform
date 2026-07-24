package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoCountry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 国家扩展信息 Mapper（GEO-001 / platform-geo-service）。
 * <p>MyBatis-Plus 基础 CRUD + 自定义查询。listEnabled 的 keyword 按 iso2 精确匹配
 * 或 name/nameEn/nameCh 前缀匹配；默认不查 icon_base64。</p>
 */
@Mapper
public interface GeoCountryMapper extends BaseMapper<GeoCountry> {

    /**
     * 查询启用状态的国家记录（不含 icon_base64）。
     *
     * @param keyword 关键词，可空；iso2 精确或名称前缀
     * @return 启用国家列表
     */
    List<GeoCountry> listEnabled(@Param("keyword") String keyword);
}

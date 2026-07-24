package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoCountry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 2026-07-23 GEO-001 国家 Mapper
 */
@Mapper
/**
 * 国家扩展信息 Mapper。MyBatis-Plus 基础 CRUD + 自定义查询。
 * 支持 keyword 模糊匹配 name/nameEn/nameCh 三列。
 */
public interface GeoCountryMapper extends BaseMapper<GeoCountry> {

    List<GeoCountry> listEnabled(@Param("keyword") String keyword);
}

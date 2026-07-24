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
public interface GeoCountryMapper extends BaseMapper<GeoCountry> {

    List<GeoCountry> listEnabled(@Param("keyword") String keyword);
}

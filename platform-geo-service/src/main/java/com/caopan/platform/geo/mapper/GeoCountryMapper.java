package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.GeoCountry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 国家扩展信息 Mapper。MyBatis-Plus 基础 CRUD + 自定义查询。
 * 支持 keyword 模糊匹配 name/nameEn/nameCh 三列。
 */
@Mapper
public interface GeoCountryMapper extends BaseMapper<GeoCountry> {

    /**
     * 查询启用状态的国家记录。
     * @param keyword 关键词，可空
     * @return 查询结果
     */
    List<GeoCountry> listEnabled(@Param("keyword") String keyword);
}

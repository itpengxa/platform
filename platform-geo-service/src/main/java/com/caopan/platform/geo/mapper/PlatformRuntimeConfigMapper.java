package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.PlatformRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlatformRuntimeConfigMapper extends BaseMapper<PlatformRuntimeConfig> {

    List<PlatformRuntimeConfig> findAll();

    PlatformRuntimeConfig findByKey(@Param("configKey") String configKey);

    int upsert(PlatformRuntimeConfig row);

    int deleteByKey(@Param("configKey") String configKey);
}

package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.PlatformApiAccessStat;
import org.apache.ibatis.annotations.Mapper;

/**
 * API 调用记录 Mapper（GEO-001 / platform-geo-service）。
 * <p>仅使用 MyBatis-Plus {@code insert} 落库；日聚合由运维 SQL 完成。</p>
 */
@Mapper
public interface PlatformApiAccessStatMapper extends BaseMapper<PlatformApiAccessStat> {
}

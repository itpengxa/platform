package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.entity.PlatformAdminSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlatformAdminSessionMapper extends BaseMapper<PlatformAdminSession> {

    AdminSessionCaller findActiveCallerByHash(@Param("tokenHash") String tokenHash);

    int revokeByTokenHash(@Param("tokenHash") String tokenHash);

    int revokeActiveByUserId(@Param("userId") Long userId);
}

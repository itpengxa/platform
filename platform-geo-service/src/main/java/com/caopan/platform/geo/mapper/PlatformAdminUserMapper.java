package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.PlatformAdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlatformAdminUserMapper extends BaseMapper<PlatformAdminUser> {

    PlatformAdminUser findByUsername(@Param("username") String username);

    long countAll();

    long countAdmin(@Param("username") String username, @Param("status") Integer status);

    List<PlatformAdminUser> pageAdmin(@Param("username") String username,
                                      @Param("status") Integer status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);
}

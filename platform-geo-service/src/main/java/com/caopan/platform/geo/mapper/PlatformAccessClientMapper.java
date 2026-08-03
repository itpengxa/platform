package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.entity.PlatformAccessClient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 接入方 Mapper（GEO-001 / platform-geo-service）。
 * <p>MyBatis-Plus 基础 CRUD + 按 client_code 查询。</p>
 */
@Mapper
public interface PlatformAccessClientMapper extends BaseMapper<PlatformAccessClient> {

    /**
     * 按接入方编码精确查询。
     *
     * @param clientCode 接入方编码
     * @return 接入方实体，不存在则 null
     */
    PlatformAccessClient findByCode(@Param("clientCode") String clientCode);

    long countAdmin(@Param("clientCode") String clientCode,
                    @Param("status") Integer status,
                    @Param("allowIssue") Integer allowIssue);

    java.util.List<PlatformAccessClient> pageAdmin(@Param("clientCode") String clientCode,
                                                   @Param("status") Integer status,
                                                   @Param("allowIssue") Integer allowIssue,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);
}

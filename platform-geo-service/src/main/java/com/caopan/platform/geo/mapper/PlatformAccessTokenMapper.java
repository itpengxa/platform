package com.caopan.platform.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.caopan.platform.geo.access.TokenCallerRow;
import com.caopan.platform.geo.entity.PlatformAccessToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 访问令牌 Mapper（GEO-001 / platform-geo-service）。
 * <p>负责吊销、按 hash 解析有效 Token（联表校验 client 启用状态）。</p>
 */
@Mapper
public interface PlatformAccessTokenMapper extends BaseMapper<PlatformAccessToken> {

    /**
     * 将该接入方下所有有效 Token 置为吊销（status=0）。
     *
     * @param clientId 接入方 id
     * @return 更新行数
     */
    int revokeActiveByClientId(@Param("clientId") Long clientId);

    /**
     * 吊销前查询仍有效的 token_hash（用于 Redis valid 清理）。
     */
    List<String> listActiveTokenHashesByClientId(@Param("clientId") Long clientId);

    /**
     * 按 token_hash 查询仍有效且所属 client 启用的 Token 行。
     *
     * @param tokenHash SHA-256 十六进制
     * @return Token 实体，不存在或无效则 null
     */
    PlatformAccessToken findActiveByHash(@Param("tokenHash") String tokenHash);

    /**
     * 按 token_hash 一次联表解析出调用方（tokenId / clientId / clientCode）。
     *
     * @param tokenHash SHA-256 十六进制
     * @return 调用方行，不存在或无效则 null
     */
    TokenCallerRow findActiveCallerByHash(@Param("tokenHash") String tokenHash);

    /**
     * 按 Token 主键反查接入方编码。
     *
     * @param tokenId Token 主键
     * @return client_code，不存在则 null
     */
    String findClientCodeByTokenId(@Param("tokenId") Long tokenId);

    long countAdmin(@Param("clientCode") String clientCode, @Param("status") Integer status);

    java.util.List<com.caopan.platform.geo.admin.access.AccessTokenAdminService.TokenAdminRow> pageAdmin(
            @Param("clientCode") String clientCode,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int revokeById(@Param("id") Long id);
}

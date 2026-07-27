package com.caopan.platform.geo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * API 访问令牌实体（GEO-001 / platform-geo-service）。
 * <p>对应表 {@code platform_access_token}。仅存 SHA-256 hash，明文只在签发接口响应中返回一次。
 * 长效无过期；同一 client 再签发时旧有效行 {@code status=0} 吊销。</p>
 */
@TableName("platform_access_token")
public class PlatformAccessToken {

    @TableId(type = IdType.AUTO)
    /** 主键 */
    private Long id;
    /** 所属接入方 id → platform_access_client.id */
    private Long clientId;
    /** 明文 Token 的 SHA-256 十六进制 */
    private String tokenHash;
    /** 明文前 8 位，仅排障，不可单独鉴权 */
    private String tokenPrefix;
    /** 状态：1 有效 0 已吊销 */
    private Integer status;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

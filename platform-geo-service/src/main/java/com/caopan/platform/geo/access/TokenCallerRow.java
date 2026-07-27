package com.caopan.platform.geo.access;

/**
 * Token 解析联表结果（GEO-001 / platform-geo-service）。
 * <p>由 {@code PlatformAccessTokenMapper#findActiveCallerByHash} 一次查出，
 * 避免先查 Token 再查 client_code 的双次往返。</p>
 */
public class TokenCallerRow {

    /** platform_access_token.id */
    private Long tokenId;
    /** platform_access_client.id */
    private Long clientId;
    /** 接入方编码 */
    private String clientCode;

    /** @return Token 主键 */
    public Long getTokenId() { return tokenId; }
    public void setTokenId(Long tokenId) { this.tokenId = tokenId; }

    /** @return 接入方主键 */
    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }

    /** @return 接入方编码 */
    public String getClientCode() { return clientCode; }
    public void setClientCode(String clientCode) { this.clientCode = clientCode; }
}

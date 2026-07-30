package com.caopan.platform.geo.access;

/**
 * Token 解析联表结果（JDK21 record）。
 * <p>由 {@code PlatformAccessTokenMapper#findActiveCallerByHash} 一次查出，
 * 避免先查 Token 再查 client_code 的双次往返。MyBatis 按组件名映射。</p>
 */
public record TokenCallerRow(Long tokenId, Long clientId, String clientCode) {

    public Long getTokenId() {
        return tokenId;
    }

    public Long getClientId() {
        return clientId;
    }

    public String getClientCode() {
        return clientCode;
    }
}

package com.caopan.platform.common.auth;

/**
 * 请求调用方上下文（JDK21 record）。
 */
public record CallerContext(Long clientId, String clientCode, Long tokenId) {

    public static CallerContext anonymous() {
        return new CallerContext(null, "anonymous", null);
    }

    /** 兼容旧 getter 调用 */
    public Long getClientId() {
        return clientId;
    }

    public String getClientCode() {
        return clientCode;
    }

    public Long getTokenId() {
        return tokenId;
    }
}

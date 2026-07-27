package com.caopan.platform.common.auth;

/**
 * 请求调用方上下文（Token 解析结果，platform-common）。
 * <p>由 {@code GeoAccessAspect} 在鉴权成功后写入 {@link CallerContextHolder}，
 * 供入参日志与 {@code platform_api_access_stat} 统计使用。鉴权关闭时使用 {@link #anonymous()}。</p>
 */
public final class CallerContext {

    /** 接入方主键，匿名时为 null */
    private final Long clientId;
    /** 接入方编码（如 crm）；匿名为 {@code anonymous} */
    private final String clientCode;
    /** 当前有效 Token 主键，匿名时为 null */
    private final Long tokenId;

    /**
     * 构造调用方上下文。
     *
     * @param clientId   接入方 id，可为 null
     * @param clientCode 接入方编码，不可为 null
     * @param tokenId    Token id，可为 null
     */
    public CallerContext(Long clientId, String clientCode, Long tokenId) {
        this.clientId = clientId;
        this.clientCode = clientCode;
        this.tokenId = tokenId;
    }

    /** @return 接入方主键，匿名时 null */
    public Long getClientId() {
        return clientId;
    }

    /** @return 接入方编码 */
    public String getClientCode() {
        return clientCode;
    }

    /** @return Token 主键，匿名时 null */
    public Long getTokenId() {
        return tokenId;
    }

    /**
     * 鉴权关闭或未识别调用方时的占位上下文。
     *
     * @return clientCode={@code anonymous} 的上下文
     */
    public static CallerContext anonymous() {
        return new CallerContext(null, "anonymous", null);
    }
}

package com.caopan.platform.geo.client;

/**
 * GEO HTTP 客户端异常。调用远程 geo 服务接口失败时抛出。
 * 包含 HTTP 状态码和错误信息，便于调用方区分网络错误和业务错误。
 */
public class GeoClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造 GeoClientException。
     * @param message message
     */
    public GeoClientException(String message) {
        super(message);
    }

    /**
     * 构造 GeoClientException。
     * @param message message
     * @param cause cause
     */
    public GeoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.caopan.platform.geo.client;

/**
 * GEO HTTP 客户端异常（platform-geo-client）。
 * <p>调用远程 geo REST 失败时抛出：含业务码非 0、空响应、网络 IO 等。
 * 由 {@link GeoHttpClient} 抛出，调用方可区分业务错误与传输错误（看 cause）。</p>
 */
public class GeoClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 仅消息构造。
     *
     * @param message 错误说明
     */
    public GeoClientException(String message) {
        super(message);
    }

    /**
     * 带根因构造（如 IOException）。
     *
     * @param message 错误说明
     * @param cause   底层异常
     */
    public GeoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

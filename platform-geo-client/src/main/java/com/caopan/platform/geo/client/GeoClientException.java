package com.caopan.platform.geo.client;

/**
 * 2026-07-24 GEO-001 HTTP 客户端异常
 */
public class GeoClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GeoClientException(String message) {
        super(message);
    }

    public GeoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.caopan.platform.common.exception;

/**
 * 2026-07-23 GEO-001 业务错误码
 */
public enum ErrorCode {

    PARENT_NOT_FOUND(40001, "父节点不存在"),
    COUNTRY_NOT_FOUND(40002, "国家不存在"),
    REGION_NOT_FOUND(40003, "区划不存在"),
    PARAM_INVALID(40000, "参数不合法");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}

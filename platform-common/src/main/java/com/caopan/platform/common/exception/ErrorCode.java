package com.caopan.platform.common.exception;

/**
 * 错误码枚举。统一管理所有业务异常码。
 * 文案通过 {@link #getMessageKey()} 走 MessageSource 做中/英国际化（W10）。
 */
public enum ErrorCode {

    PARAM_INVALID(40000, "error.param_invalid", "参数不合法"),
    PARENT_NOT_FOUND(40001, "error.parent_not_found", "父节点不存在"),
    COUNTRY_NOT_FOUND(40002, "error.country_not_found", "国家不存在"),
    REGION_NOT_FOUND(40003, "error.region_not_found", "区划不存在"),
    RATE_LIMITED(40029, "error.rate_limited", "请求过于频繁，请稍后再试"),
    UNAUTHORIZED(40100, "error.unauthorized", "未授权访问"),
    SYSTEM_ERROR(50000, "error.system", "系统异常");

    private final int code;
    private final String messageKey;
    /** 缺省中文，MessageSource 未加载时回退 */
    private final String defaultMessage;

    ErrorCode(int code, String messageKey, String defaultMessage) {
        this.code = code;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getMessage() {
        return defaultMessage;
    }
}

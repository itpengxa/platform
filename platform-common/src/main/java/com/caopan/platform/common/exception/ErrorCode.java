package com.caopan.platform.common.exception;

/**
 * 错误码枚举（platform-common）。
 * <p>统一管理业务异常码；文案通过 {@link #getMessageKey()} 走 MessageSource
 * 做中/英国际化，缺省中文见 {@link #getMessage()}。</p>
 */
public enum ErrorCode {

    /** 请求参数不合法（含校验失败、深度越界、关键词非法等） */
    PARAM_INVALID(40000, "error.param_invalid", "参数不合法"),
    /** 父节点不存在或未启用（子级查询） */
    PARENT_NOT_FOUND(40001, "error.parent_not_found", "父节点不存在"),
    /** 国家不存在或未启用 */
    COUNTRY_NOT_FOUND(40002, "error.country_not_found", "国家不存在"),
    /** 区划节点不存在或未启用 */
    REGION_NOT_FOUND(40003, "error.region_not_found", "区划不存在"),
    /** IP 限流触发（HTTP 429） */
    RATE_LIMITED(40029, "error.rate_limited", "请求过于频繁，请稍后再试"),
    /** 内部 Token 鉴权失败（HTTP 401） */
    UNAUTHORIZED(40100, "error.unauthorized", "未授权访问"),
    /** 未捕获的系统异常 */
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

    /**
     * @return 数值错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * @return MessageSource 键（如 error.param_invalid）
     */
    public String getMessageKey() {
        return messageKey;
    }

    /**
     * @return 缺省中文文案
     */
    public String getMessage() {
        return defaultMessage;
    }
}

package com.caopan.platform.common.exception;

/**
 * 错误码枚举（platform-common）。
 * <p>统一管理业务异常码；文案为英文（当前仅英文方案）。
 * 可通过 MessageSource（{@code messages.properties}）覆盖，缺省见 {@link #getMessage()}。</p>
 */
public enum ErrorCode {

    /** 请求参数不合法（含校验失败、深度越界、关键词非法等） */
    PARAM_INVALID(40000, "error.param_invalid", "Invalid parameter"),
    /** 父节点不存在或未启用（子级查询） */
    PARENT_NOT_FOUND(40001, "error.parent_not_found", "Parent region not found"),
    /** 国家不存在或未启用 */
    COUNTRY_NOT_FOUND(40002, "error.country_not_found", "Country not found"),
    /** 区划节点不存在或未启用 */
    REGION_NOT_FOUND(40003, "error.region_not_found", "Region not found"),
    /** IP 限流触发（HTTP 429） */
    RATE_LIMITED(40029, "error.rate_limited", "Too many requests, please retry later"),
    /** 内部 Token 鉴权失败（HTTP 401） */
    UNAUTHORIZED(40100, "error.unauthorized", "Unauthorized"),
    /** 未捕获的系统异常 */
    SYSTEM_ERROR(50000, "error.system", "Internal server error");

    private final int code;
    private final String messageKey;
    /** 缺省英文文案，MessageSource 未加载时回退 */
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
     * @return 缺省英文文案
     */
    public String getMessage() {
        return defaultMessage;
    }
}

package com.caopan.platform.common.api;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 响应体（JDK21 record，不可变）。
 */
public record Result<T>(int code, String message, T data) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** Jackson / 旧调用兼容 */
    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}

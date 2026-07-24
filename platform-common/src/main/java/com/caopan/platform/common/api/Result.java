package com.caopan.platform.common.api;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 响应体（platform-common）。
 * <p>所有对外 HTTP 接口返回此结构：code=0 表示成功，非 0 为业务/系统错误码
 * （与 {@link com.caopan.platform.common.exception.ErrorCode} 对齐）；泛型 data 承载业务数据。</p>
 */
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务码，0=成功 */
    private int code;
    /** 提示信息（可按 Locale 国际化） */
    private String message;
    /** 业务数据 */
    private T data;

    /**
     * 构造成功响应。
     *
     * @param data 业务数据，可为 null
     * @return code=0、message=success 的结果
     */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /**
     * 构造失败响应。
     *
     * @param code    业务错误码
     * @param message 错误提示文案
     * @return data 为 null 的失败结果
     */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(null);
        return r;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}

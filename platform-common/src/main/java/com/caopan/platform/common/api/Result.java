package com.caopan.platform.common.api;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 响应体。所有接口返回此结构。
 * code=0 表示成功，非 0 表示业务异常。
 * 泛型 data 承载具体业务数据。
 */
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务码，0=成功 */
    private int code;
    /** 提示信息 */
    private String message;
    /** 业务数据 */
    private T data;

    /**
     * 成功响应。
     * @param data data
     * @return 查询结果
     */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /**
     * 失败响应。
     * @param code code
     * @param message message
     * @return 查询结果
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

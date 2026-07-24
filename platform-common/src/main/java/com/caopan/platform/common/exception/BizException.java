package com.caopan.platform.common.exception;

/**
 * 业务异常（platform-common）。
 * <p>业务校验失败时抛出，由 bootstrap 模块 {@code GlobalExceptionHandler} 捕获，
 * 并转换为标准 {@link com.caopan.platform.common.api.Result} 返回客户端。</p>
 */
public class BizException extends RuntimeException {

    /** 业务错误码，对应 {@link ErrorCode#getCode()} */
    private final int code;

    /**
     * 按错误码枚举构造（文案取枚举缺省中文）。
     *
     * @param errorCode 错误码枚举，不可为 null
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 按自定义码与文案构造。
     *
     * @param code    业务错误码
     * @param message 错误提示
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }
}

package com.caopan.platform.common.exception;

/**
 * 业务异常。当业务校验不通过时抛出，由 GlobalExceptionHandler 统一捕获并
 * 转换为标准 Result 响应返回给客户端。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

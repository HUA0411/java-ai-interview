package com.guohua.interview.common;

import lombok.Getter;

/**
 * 业务异常：携带业务错误码与消息，由全局异常处理器统一转为 Result
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }
}

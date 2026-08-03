package com.guohua.interview.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 返回结构
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 0 = 成功，非 0 = 业务错误码 */
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(0, "success", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}

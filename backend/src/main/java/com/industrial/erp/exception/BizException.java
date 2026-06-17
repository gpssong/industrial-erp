package com.industrial.erp.exception;

public class BizException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private int code = 500;
    private String message;

    public BizException(String message) {
        super(message);
        this.message = message;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.message = message;
    }

    public static BizException of(String message) {
        return new BizException(message);
    }

    public static BizException of(int code, String message) {
        return new BizException(code, message);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}

package com.woowangthon.backend.common;

import org.springframework.http.HttpStatus;

/** 계약의 오류 응답 { "error": { code, message } } 으로 바뀌는 예외. code 는 계약에 적힌 값만 쓴다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    // 409 SOURCE_DUPLICATE 가 이미 있는 원본을 알려 줄 때만(S-69)
    private final ErrorResponse.SourceRef source;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, ErrorResponse.SourceRef source) {
        super(message);
        this.status = status;
        this.code = code;
        this.source = source;
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public ErrorResponse.SourceRef source() {
        return source;
    }
}

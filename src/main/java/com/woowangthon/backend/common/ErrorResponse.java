package com.woowangthon.backend.common;

public record ErrorResponse(Error error) {

    public record Error(String code, String message) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Error(code, message));
    }
}

package com.woowangthon.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 모든 4xx · 5xx 를 계약 README 「오류 응답」 모양 하나로 돌려준다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REQUEST", "요청 형식이 올바르지 않습니다"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        // 없는 주소 · 허용되지 않은 메서드 · Content-Type 누락 같은 Spring 기본 오류는 그 상태 코드 그대로
        if (e instanceof org.springframework.web.ErrorResponse spring) {
            HttpStatus status = HttpStatus.valueOf(spring.getStatusCode().value());
            return ResponseEntity.status(status).body(ErrorResponse.of(status.name(), status.getReasonPhrase()));
        }
        log.error("처리하지 못한 오류", e);
        return ResponseEntity.internalServerError().body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류"));
    }
}

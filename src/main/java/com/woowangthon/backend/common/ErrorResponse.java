package com.woowangthon.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

public record ErrorResponse(Error error) {

    /** source 는 409 SOURCE_DUPLICATE 에만 싣는다(S-69) — 그 밖엔 JSON 에 나오지 않는다. */
    public record Error(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) SourceRef source) {

        public Error(String code, String message) {
            this(code, message, null);
        }
    }

    /** 이미 있는 원본 — 이름과 넣은 때만(원문 · 해시는 싣지 않는다). */
    public record SourceRef(Long id, String name, OffsetDateTime createdAt) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Error(code, message));
    }
}

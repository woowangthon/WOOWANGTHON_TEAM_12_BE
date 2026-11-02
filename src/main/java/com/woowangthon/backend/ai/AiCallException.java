package com.woowangthon.backend.ai;

/**
 * AI 추출이 실패한 이유. code 는 계약 「작업 상태」의 error.code 값
 * (AI_TIMEOUT · AI_UNAVAILABLE · AI_FAILED)만 쓴다.
 */
public class AiCallException extends RuntimeException {

    private final String code;

    public AiCallException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** AI 서버가 200 을 줬지만 응답이 계약(ai-api.md 「응답 규칙」)을 벗어났다. */
    public static AiCallException invalidResponse(String detail) {
        return new AiCallException("AI_FAILED", "INVALID_RESPONSE: " + detail);
    }

    public String code() {
        return code;
    }
}

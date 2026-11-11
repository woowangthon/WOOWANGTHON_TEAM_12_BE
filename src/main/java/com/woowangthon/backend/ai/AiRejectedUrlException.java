package com.woowangthon.backend.ai;

/**
 * AI 서버가 공고 링크 · 캡처를 못 읽었다(ai-api.md 422 URL_BLOCKED · URL_UNREADABLE · IMAGE_UNREADABLE).
 * code · message 는 AI 가 준 그대로 — message 는 사용자에게 그대로 보이는 한국어 한 줄이다. BE 는 FE 에 422 로 그대로 넘긴다.
 */
public class AiRejectedUrlException extends AiCallException {

    public AiRejectedUrlException(String code, String message) {
        super(code, message);
    }
}

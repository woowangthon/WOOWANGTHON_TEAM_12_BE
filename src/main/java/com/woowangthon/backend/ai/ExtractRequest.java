package com.woowangthon.backend.ai;

/** ai-api.md 「POST /extract」 요청. text 는 저장한 원본과 글자 하나 다르지 않게 보낸다. */
public record ExtractRequest(String sourceName, String text) {
}

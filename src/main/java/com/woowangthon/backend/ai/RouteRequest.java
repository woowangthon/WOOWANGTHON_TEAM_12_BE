package com.woowangthon.backend.ai;

/** ai-api.md 「POST /route」 요청 — 홈 입력의 앞 4000자(코드 포인트)와 자르기 전 전체 길이(코드 포인트). */
public record RouteRequest(String text, int totalLength) {
}

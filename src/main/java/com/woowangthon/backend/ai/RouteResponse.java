package com.woowangthon.backend.ai;

import com.woowangthon.backend.entrance.EntranceKind;

/** ai-api.md 「POST /route」 응답. kind 가 값 목록 밖이면 JSON 을 읽지 못해 AI_FAILED 다. */
public record RouteResponse(EntranceKind kind, String reason, String model) {
}

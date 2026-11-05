package com.woowangthon.backend.ai;

import java.util.List;

/**
 * ai-api.md 「POST /ground-check」 요청 — 점검할 글과 그 사용자의 경험 전부(경험은 /match 와 같은 모양). 근거는 원본에서 찾은 것(matched)만.
 * 경험이 없으면 experiences 는 [].
 */
public record GroundCheckRequest(String text, List<MatchRequest.ExperiencePart> experiences) {
}

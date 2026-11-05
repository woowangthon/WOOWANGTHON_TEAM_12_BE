package com.woowangthon.backend.ai;

import java.util.List;

/** ai-api.md 「POST /ask」 요청 — 한 줄 질문과 그 사용자의 경험 전부(「POST /match」 experiences 와 같은 모양). 1개 이상. */
public record AskRequest(String query, List<MatchRequest.ExperiencePart> experiences) {
}

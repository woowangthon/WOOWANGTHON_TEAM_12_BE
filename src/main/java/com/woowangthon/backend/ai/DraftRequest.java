package com.woowangthon.backend.ai;

import java.util.List;

/**
 * ai-api.md 「POST /draft」 요청 — 문항과 사용자가 고른 경험만, 고른 순서대로(경험은 /match 와 같은 모양). 근거는 원본에서 찾은 것(matched)만.
 */
public record DraftRequest(MatchRequest.QuestionPart question, List<MatchRequest.ExperiencePart> experiences) {
}

package com.woowangthon.backend.ai;

import com.woowangthon.backend.activity.Period;
import java.util.List;

/**
 * ai-api.md 「POST /new-facts」 요청 — 확정 답, 그 사용자의 경험 전부(/ground-check 와 같은 모양 · 원본에서 찾은 근거만), 활동 전부,
 * 후보로 삼지 않을 문장(저장된 초안 문장 · 「저장 안 함」 한 문장). 없으면 각각 [].
 */
public record NewFactsRequest(String text, List<MatchRequest.ExperiencePart> experiences, List<ActivityPart> activities,
        List<String> excludedSentences) {

    public record ActivityPart(Long id, String name, Period period) {
    }
}

package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ai-api.md 「POST /ask」 응답 200 — 추천 0 ~ 3개. 칸 규칙은 「POST /match」 recommendations 와 같다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AskResponse(List<Recommendation> recommendations, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recommendation(Long experienceId, String reason, List<MatchResponse.EvidenceResult> evidence) {
    }
}

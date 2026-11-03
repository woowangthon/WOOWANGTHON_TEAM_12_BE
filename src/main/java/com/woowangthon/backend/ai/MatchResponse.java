package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * ai-api.md 「POST /match」 응답 200. 값 목록(status · type)은 문자열로 받아 저장할 때 확인한다 —
 * 틀리면 어느 칸이 틀렸는지 실패 이유에 남기려고.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchResponse(
        List<RequirementResult> requirements,
        List<RecommendationResult> recommendations,
        List<ClarificationResult> clarifications,
        String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequirementResult(String text, String status, List<Long> experienceIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecommendationResult(
            Long experienceId,
            String reason,
            String emphasis,
            List<EvidenceResult> evidence,
            List<String> gaps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceResult(String type, Long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClarificationResult(Long experienceId, String question) {
    }
}

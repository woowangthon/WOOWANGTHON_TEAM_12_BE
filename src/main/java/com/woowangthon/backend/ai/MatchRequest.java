package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.SourceStatus;
import java.util.List;

/**
 * ai-api.md 「POST /match」 요청 — 문항과 그 사용자의 경험 전부(ADR-003). 경험이 없으면 experiences 는 []. activityId 는 추천의 활동 수를 세는 데
 * 쓰며(S-84) 매칭에만 싣는다 — 초안 요청의 경험엔 null 이라 칸이 없다.
 */
public record MatchRequest(QuestionPart question, List<ExperiencePart> experiences) {

    public record QuestionPart(String organization, String text) {
    }

    public record ExperiencePart(
            Long id,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long activityId,
            String activityName,
            Period period,
            String content,
            List<Competency> competencies,
            Actor actor,
            SourceStatus sourceStatus,
            List<CitationPart> citations,
            List<SupplementPart> supplements) {
    }

    public record CitationPart(Long id, String quote) {
    }

    public record SupplementPart(Long id, String question, String answer) {
    }
}

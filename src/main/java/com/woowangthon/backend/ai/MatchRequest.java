package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.Ksa;
import com.woowangthon.backend.question.PostingSection;
import java.util.List;

/**
 * ai-api.md 「POST /match」 요청 — 문항(+ 지원의 직무 · 공고 줄)과 그 사용자의 경험 전부(ADR-003). 경험이 없으면 experiences 는 []. activityId 는 추천의 활동 수를 세는 데
 * 쓰며(S-84) 매칭에만 싣는다 — 초안 요청의 경험엔 null 이라 칸이 없다.
 */
public record MatchRequest(QuestionPart question, List<ExperiencePart> experiences) {

    /**
     * jobRole · postingLines — 그 지원의 직무 · 공고 요건 줄(선택, ETC#326). 추천 순서 · 요구사항 말을 고르는 맥락일 뿐이다.
     * 직무가 없으면 칸을 싣지 않고, 줄이 없으면 칸을 싣지 않는다(「없거나 [] 면 문항만 본다」).
     */
    public record QuestionPart(
            String organization,
            String text,
            @JsonInclude(JsonInclude.Include.NON_NULL) JobRole jobRole,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PostingLinePart> postingLines) {
    }

    // question-api Application.postingLines 에서 id · readFrom 을 뺀 것
    public record PostingLinePart(PostingSection section, String text, Ksa ksa) {
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
            List<SupplementPart> supplements,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> askedQuestions) {
    }

    public record CitationPart(Long id, String quote) {
    }

    public record SupplementPart(Long id, String question, String answer) {
    }
}

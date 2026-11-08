package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.woowangthon.backend.question.DraftFrame;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.RequirementStatus;
import java.util.List;
import java.util.stream.Stream;

/**
 * ai-api.md 「POST /draft」 요청 — 문항과 사용자가 고른 경험, 고른 순서대로(경험은 /match 와 같은 모양). 근거는 원본에서 찾은 것(matched)만.
 * 저장된 매칭 결과(요구사항 · 그 경험의 이유 · 강조점 · 부족한 점)와 지원의 직무를 선택 칸으로 싣는다(ETC#141) — 없으면(null) 칸을 싣지 않는다.
 * 구성 틀(NONE 이면 null) · 켜 둔 지침(없으면 null) · 목표 글자 수(없으면 null)도 같다(S-49 · S-50 · S-77). 같은 활동의 다른 경험은
 * contextExperiences 로 따로 싣는다 — 사용자가 끄면 null(S-75).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DraftRequest(QuestionPart question, List<ExperiencePart> experiences,
        List<MatchRequest.ExperiencePart> contextExperiences, List<GuidelinePart> guidelines) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionPart(String organization, String text, JobRole jobRole, DraftFrame frame,
            List<RequirementPart> requirements, Integer targetLength) {
    }

    /** 켜 둔 작성 지침 — 출처는 싣지 않는다. */
    public record GuidelinePart(Long id, String text) {
    }

    /** question-api `Requirement` 에서 experienceIds 를 뺀 것. */
    public record RequirementPart(String text, RequirementStatus status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExperiencePart(@JsonUnwrapped MatchRequest.ExperiencePart base, String reason, String emphasis,
            List<String> gaps) {

        @JsonIgnore
        public Long id() {
            return base.id();
        }
    }

    /** 근거 ID 확인용 — 고른 경험만, /match 모양 그대로. */
    public List<MatchRequest.ExperiencePart> baseExperiences() {
        return experiences.stream().map(ExperiencePart::base).toList();
    }

    /** 근거 ID 확인용 — 고른 경험과 보조 재료 경험. */
    public List<MatchRequest.ExperiencePart> allExperiences() {
        return contextExperiences == null ? baseExperiences()
                : Stream.concat(baseExperiences().stream(), contextExperiences.stream()).toList();
    }
}

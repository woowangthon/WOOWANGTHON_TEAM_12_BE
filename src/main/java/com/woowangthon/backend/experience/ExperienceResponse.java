package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.Times;
import java.time.OffsetDateTime;
import java.util.List;

/** 계약의 Experience 모양. */
public record ExperienceResponse(
        Long id,
        Long activityId,
        String content,
        List<Competency> competencies,
        CompetenciesBy competenciesBy,
        Actor actor,
        SourceStatus sourceStatus,
        List<CitationResponse> citations,
        List<SupplementResponse> supplements) {

    public record CitationResponse(
            Long id,
            Long sourceId,
            String sourceName,
            String quote,
            Boolean matched,
            Location location) {

        static CitationResponse from(Citation c) {
            Location location = c.getStartLine() == null ? null : new Location(c.getStartLine(), c.getEndLine());
            return new CitationResponse(c.getId(), c.getSource().getId(), c.getSource().getName(), c.getQuote(),
                    c.getMatched(), location);
        }
    }

    public record Location(Integer startLine, Integer endLine) {
    }

    /** 계약의 Supplement 모양 — 늘 「내가 입력」이라 출처 칸이 없다. */
    public record SupplementResponse(
            Long id,
            String question,
            String answer,
            Long questionId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public static SupplementResponse from(Supplement s) {
            return new SupplementResponse(s.getId(), s.getQuestion(), s.getAnswer(), s.getQuestionId(),
                    Times.seoul(s.getCreatedAt()), Times.seoul(s.getUpdatedAt()));
        }
    }

    public static ExperienceResponse from(Experience e) {
        return new ExperienceResponse(e.getId(), e.getActivity().getId(), e.getContent(), e.getCompetencies(),
                e.getCompetenciesBy(), e.getActor(), e.getSourceStatus(), e.getCitations().stream().map(CitationResponse::from).toList(),
                e.getSupplements().stream().map(SupplementResponse::from).toList());
    }
}

package com.woowangthon.backend.experience;

import java.util.List;

/** 계약의 Experience 모양. */
public record ExperienceResponse(
        Long id,
        Long activityId,
        String content,
        List<Competency> competencies,
        Actor actor,
        SourceStatus sourceStatus,
        List<CitationResponse> citations) {

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

    public static ExperienceResponse from(Experience e) {
        return new ExperienceResponse(e.getId(), e.getActivity().getId(), e.getContent(), e.getCompetencies(),
                e.getActor(), e.getSourceStatus(), e.getCitations().stream().map(CitationResponse::from).toList());
    }
}

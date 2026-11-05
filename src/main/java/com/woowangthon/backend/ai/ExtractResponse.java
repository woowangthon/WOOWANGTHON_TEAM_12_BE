package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * ai-api.md 「POST /extract」 응답 200. 값 목록(actor · sourceStatus)은 문자열로 받아
 * 저장할 때 확인한다 — 틀리면 어느 칸이 틀렸는지 실패 이유에 남기려고.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractResponse(List<ActivityResult> activities, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityResult(String name, PeriodResult period, Long sameAsActivityId,
            List<ExperienceResult> experiences) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodResult(String start, String end, Boolean ongoing) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExperienceResult(
            String content,
            List<String> competencies,
            String actor,
            String sourceStatus,
            List<CitationResult> citations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CitationResult(String quote, Boolean matched, LocationResult location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationResult(Integer startLine, Integer endLine) {
    }
}

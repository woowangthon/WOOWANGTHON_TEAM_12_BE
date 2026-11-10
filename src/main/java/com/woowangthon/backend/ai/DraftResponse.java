package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * ai-api.md 「POST /draft」 응답 200. 값 목록(type)은 문자열로 받아 저장할 때 확인한다. omitted 가 예전 문자열 배열이면 읽지 못해 AI_FAILED
 * 가 된다 — 객체로 추측해 고치지 않는다(ETC#139).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftResponse(List<SentenceResult> sentences, List<OmittedResult> omitted,
        List<GuidelineResult> guidelines, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SentenceResult(String kind, String text, List<MatchResponse.EvidenceResult> evidence, String note,
            Boolean specific) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OmittedResult(String text, String question, Long experienceId, String placeholder,
            Integer sentenceIndex) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuidelineResult(Long id, Boolean applied, String note) {
    }
}

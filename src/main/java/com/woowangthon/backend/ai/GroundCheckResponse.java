package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ai-api.md 「POST /ground-check」 응답 200. 값 목록(status · type)은 문자열로 받아 저장할 때 확인한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroundCheckResponse(List<SentenceResult> sentences, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SentenceResult(String text, String status, List<MatchResponse.EvidenceResult> evidence, String note) {
    }
}

package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ai-api.md 「POST /new-facts」 응답 200. actor 는 문자열로 받아 저장할 때 확인한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewFactsResponse(List<Candidate> candidates, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(String sentence, String content, String actor, Long activityId) {
    }
}

package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ai-api.md 「POST /guidelines」 응답 200. 규칙은 GuidelineService 가 확인한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuidelinesResponse(List<Line> guidelines, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String text) {
    }
}

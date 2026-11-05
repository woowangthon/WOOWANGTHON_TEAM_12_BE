package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ai-api.md 「POST /link-suggestions」 응답 200 — 제안 0 ~ 3개. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LinkSuggestionResponse(List<Suggestion> suggestions, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Suggestion(Long toActivityId, String reason) {
    }
}

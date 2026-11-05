package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

/** ai-api.md 「POST /parse-application」 요청 — 붙여 넣은 글 `text` 나 공고 링크 `url`(S-46) 중 하나만 싣는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParseApplicationRequest(String text, String url) {

    public static ParseApplicationRequest ofText(String text) {
        return new ParseApplicationRequest(text, null);
    }

    public static ParseApplicationRequest ofUrl(String url) {
        return new ParseApplicationRequest(null, url);
    }
}

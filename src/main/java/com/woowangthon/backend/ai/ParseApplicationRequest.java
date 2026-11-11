package com.woowangthon.backend.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** ai-api.md 「POST /parse-application」 요청 — text · url · images 중 하나만 싣는다(S-30 · S-46 · S-92). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParseApplicationRequest(String text, String url, List<Image> images) {

    public record Image(String mediaType, String data) {
        @Override
        public String toString() { return "Image[redacted]"; }
    }

    public static ParseApplicationRequest ofText(String text) {
        return new ParseApplicationRequest(text, null, null);
    }

    public static ParseApplicationRequest ofUrl(String url) {
        return new ParseApplicationRequest(null, url, null);
    }

    public static ParseApplicationRequest ofImages(List<Image> images) {
        return new ParseApplicationRequest(null, null, images);
    }

    @Override
    public String toString() { return "ParseApplicationRequest[redacted]"; }
}

package com.woowangthon.backend.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 공고 링크 모양 확인(S-46) — entrance-api `parse-application` 의 `url` 과 question-api 지원의 `postingUrl`.
 * 모양만 본다 — BE 는 이 링크로 밖에 나가지 않는다(받기 · 내부 주소 막기는 AI 서버, ai-api 「링크 받기」).
 */
public final class PostingUrls {

    public static final int MAX_LENGTH = 2_000;

    private PostingUrls() {
    }

    /** http:// · https:// 이고 호스트가 있으며 2000자 이하인가. */
    public static boolean isValid(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}

package com.woowangthon.backend.experience;

/** 「적혀 있나」(ai-api.md) — 양쪽의 연속 공백(줄바꿈 포함)을 공백 하나로 줄인 뒤 부분 문자열이면 적혀 있다. */
public final class StarText {

    private StarText() {
    }

    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** part 가 비지 않았고 whole 에 적혀 있나. */
    public static boolean contains(String whole, String part) {
        String p = normalize(part);
        return !p.isEmpty() && normalize(whole).contains(p);
    }
}

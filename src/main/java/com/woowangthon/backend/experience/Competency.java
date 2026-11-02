package com.woowangthon.backend.experience;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 역량 — 닫힌 목록 13개(ETC docs/contracts/README.md 「값 목록」, SPEC 미결정 2).
 * 경험 하나에 0 ~ 3개, 중복 없음, 앞이 더 잘 맞는 것. 원문 사실이 아니라 해석이다.
 */
public enum Competency {
    IMPLEMENTATION,
    PROBLEM_SOLVING,
    PLANNING,
    ANALYSIS,
    OPERATION,
    COLLABORATION,
    COMMUNICATION,
    LEADERSHIP,
    INITIATIVE,
    RESPONSIBILITY,
    PERSEVERANCE,
    LEARNING,
    PRESENTATION;

    public static final int MAX_PER_EXPERIENCE = 3;

    public static Optional<Competency> parse(String value) {
        for (Competency c : values()) {
            if (c.name().equals(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** 사용자 요청 값 — 목록 밖 값 · 중복 · 4개 이상이면 IllegalArgumentException(메시지는 오류 응답에 쓴다). */
    public static List<Competency> parseStrict(List<?> values) {
        if (values.size() > MAX_PER_EXPERIENCE) {
            throw new IllegalArgumentException("competencies 는 " + MAX_PER_EXPERIENCE + "개까지입니다");
        }
        List<Competency> result = new ArrayList<>();
        Set<Competency> seen = new HashSet<>();
        for (Object value : values) {
            Competency c = (value instanceof String s ? parse(s) : Optional.<Competency>empty())
                    .orElseThrow(() -> new IllegalArgumentException("competencies 에 목록 밖 값이 있습니다: " + value));
            if (!seen.add(c)) {
                throw new IllegalArgumentException("competencies 에 같은 값이 두 번 있습니다: " + value);
            }
            result.add(c);
        }
        return result;
    }
}

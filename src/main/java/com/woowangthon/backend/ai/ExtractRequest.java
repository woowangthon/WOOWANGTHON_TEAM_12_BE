package com.woowangthon.backend.ai;

import com.woowangthon.backend.activity.Period;
import java.util.List;

/**
 * ai-api.md 「POST /extract」 요청. text 는 저장한 원본과 글자 하나 다르지 않게 보낸다.
 * existingActivities 는 이 사용자가 이미 가진 활동의 이름 · 기간만(S-44 — 경험 본문은 보내지 않는다).
 * targetActivityId 는 활동에 자료 더하기(S-60)의 대상 — 늘 existingActivities 안에 있다.
 */
public record ExtractRequest(String sourceName, String text, List<ExistingActivity> existingActivities,
        Long targetActivityId) {

    public ExtractRequest(String sourceName, String text) {
        this(sourceName, text, List.of(), null);
    }

    public record ExistingActivity(Long id, String name, Period period) {
    }
}

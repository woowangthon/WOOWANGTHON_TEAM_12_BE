package com.woowangthon.backend.ai;

import com.woowangthon.backend.activity.Period;
import java.util.List;

/** ai-api.md 「POST /link-suggestions」 요청 — 계기가 있는 활동과 후보 1 ~ 20개. 경험은 id · 본문만 싣는다. */
public record LinkSuggestionRequest(ActivityPart from, List<ActivityPart> candidates) {

    public record ActivityPart(Long id, String name, Period period, String note, List<ExperiencePart> experiences) {
    }

    public record ExperiencePart(Long id, String content) {
    }
}

package com.woowangthon.backend.ai;

import java.util.List;

/** ai-api.md 「POST /star」 응답. 칸 · 출처 · 글은 BE 가 다시 확인한다(StarService). */
public record StarResponse(List<ExperienceResult> experiences, String model) {

    public record ExperienceResult(Long id, List<SlotResult> slots) {
    }

    public record SlotResult(String slot, String sourceType, Long sourceId, String text, String question) {
    }
}

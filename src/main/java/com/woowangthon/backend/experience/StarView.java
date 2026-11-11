package com.woowangthon.backend.experience;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.ArrayList;
import java.util.List;

/**
 * 계약의 Star 모양(experience-api.md 「STAR 칸 Star」). 활동 세부의 경험 · 칸 답하기 · 칸 고치기 응답에만 싣는다 —
 * 경험 찾기 · MCP · 공개 프로필 · AI 요청엔 싣지 않는다.
 */
public record StarView(StarStatus status, List<Slot> slots) {

    public record Slot(StarSlotName slot, String text, StarSourceType sourceType, Long sourceId, String question) {
    }

    /** 칸 답하기 · 고치기 응답 — 그 Experience 에 star 를 더한 것. */
    public record WithExperience(@JsonUnwrapped ExperienceResponse experience, StarView star) {

        public static WithExperience from(Experience e) {
            return new WithExperience(ExperienceResponse.from(e), StarView.from(e));
        }
    }

    /**
     * NONE · ANALYZING 이면 칸 없음, 그 밖엔 정리된 적이 있으면 S · T · A · R 넷. 가리키던 근거 · 보완이 이 경험에 없으면 빈칸으로 싣는다.
     * 찬 칸의 질문은 싣지 않는다(빈칸이 되면 다시 보인다).
     */
    public static StarView from(Experience e) {
        if (!e.hasStarSlots()) {
            return new StarView(e.getStarStatus(), List.of());
        }
        List<Slot> slots = new ArrayList<>();
        for (StarSlotName name : StarSlotName.values()) {
            StarSlot stored = e.getStarSlots().stream().filter(s -> s.getSlot() == name).findFirst().orElse(null);
            slots.add(view(e, name, stored));
        }
        return new StarView(e.getStarStatus(), slots);
    }

    private static Slot view(Experience e, StarSlotName name, StarSlot stored) {
        if (stored == null) {
            return new Slot(name, null, null, null, null);
        }
        if (stored.isFilled() && sourceExists(e, stored)) {
            return new Slot(name, stored.getText(), stored.getSourceType(), stored.getSourceId(), null);
        }
        return new Slot(name, null, null, null, stored.getQuestion());
    }

    /** 칸이 가리키는 것이 지금 이 경험의 근거 · 보완인가. */
    static boolean sourceExists(Experience e, StarSlot slot) {
        long id = slot.getSourceId();
        return switch (slot.getSourceType()) {
            case CITATION -> e.getCitations().stream().anyMatch(c -> c.getId() == id);
            case SUPPLEMENT -> e.getSupplements().stream().anyMatch(s -> s.getId() == id);
        };
    }
}

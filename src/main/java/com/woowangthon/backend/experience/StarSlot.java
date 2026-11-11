package com.woowangthon.backend.experience;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * STAR 칸 하나(S-95) — 같은 경험의 근거 · 보완 글(의 일부)을 가리키거나, 비어 있고 질문이 붙는다.
 * 바꿀 땐 새 값으로 갈아 끼운다(경험의 칸 목록이 통째로 다시 쓰인다).
 */
@Embeddable
public class StarSlot {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StarSlotName slot;

    private String text;

    @Enumerated(EnumType.STRING)
    private StarSourceType sourceType;

    private Long sourceId;

    private String question;

    protected StarSlot() {
    }

    private StarSlot(StarSlotName slot, String text, StarSourceType sourceType, Long sourceId, String question) {
        this.slot = slot;
        this.text = text;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.question = question;
    }

    public static StarSlot filled(StarSlotName slot, String text, StarSourceType sourceType, long sourceId) {
        return new StarSlot(slot, text, sourceType, sourceId, null);
    }

    public static StarSlot empty(StarSlotName slot, String question) {
        return new StarSlot(slot, null, null, null, question);
    }

    /** 칸을 비운다 — 질문은 남긴다. */
    StarSlot cleared() {
        return empty(slot, question);
    }

    /** 이 칸을 보완 하나로 채운다 — 질문은 남긴다(빈칸으로 돌아가면 다시 보인다). */
    StarSlot pointingTo(Supplement supplement, String newText) {
        return new StarSlot(slot, newText, StarSourceType.SUPPLEMENT, supplement.getId(), question);
    }

    boolean isFilled() {
        return sourceId != null;
    }

    boolean points(StarSourceType type, long id) {
        return sourceType == type && sourceId != null && sourceId == id;
    }

    public StarSlotName getSlot() {
        return slot;
    }

    public String getText() {
        return text;
    }

    public StarSourceType getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getQuestion() {
        return question;
    }
}

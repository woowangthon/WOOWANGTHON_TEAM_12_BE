package com.woowangthon.backend.experience;

import java.util.Optional;

/** STAR 칸 — README 「값 목록」 starSlot (S-95). 선언 순서가 응답 순서다. R 은 「결과 및 배운 점」. */
public enum StarSlotName {
    S("상황"), T("과제 · 내 몫"), A("행동과 노력"), R("결과 및 배운 점");

    private final String label;

    StarSlotName(String label) {
        this.label = label;
    }

    /** 칸 이름 — 질문 없는 칸에 답하거나 칸을 고칠 때 보완의 question 이 된다(계약 「빈칸에 답하기」). */
    public String label() {
        return label;
    }

    public static Optional<StarSlotName> parse(String value) {
        for (StarSlotName slot : values()) {
            if (slot.name().equals(value)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }
}

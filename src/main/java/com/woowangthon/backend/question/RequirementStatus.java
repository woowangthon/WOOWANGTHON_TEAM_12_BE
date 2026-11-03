package com.woowangthon.backend.question;

/** 요구사항 판정 — 근거 있음 / 일부 / 자료에서 확인 안 됨. NOT_FOUND 는 「경험 없음」이 아니다(SPEC §0 · S-19). */
public enum RequirementStatus {
    SUPPORTED,
    PARTIAL,
    NOT_FOUND
}

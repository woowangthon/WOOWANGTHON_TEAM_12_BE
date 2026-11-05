package com.woowangthon.backend.question;

/** 근거 점검 판정(README 「값 목록」 groundingStatus) — 원문 · 내가 입력 · 근거 없음 · 생각. */
public enum GroundingStatus {
    SOURCE,
    USER_INPUT,
    UNSUPPORTED,
    OPINION
}

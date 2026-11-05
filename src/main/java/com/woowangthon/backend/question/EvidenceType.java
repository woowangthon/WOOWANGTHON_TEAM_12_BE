package com.woowangthon.backend.question;

/**
 * 받치는 것의 종류 — 원본 발췌문(근거) / 되묻기에 사용자가 한 답(보완) / 사용자가 직접 넣거나 고친 경험 본문.
 * EXPERIENCE 는 근거 점검(S-45) · 답변 초안에서만 쓴다 — 추천(/match)엔 쓰지 않는다(README 「값 목록」).
 */
public enum EvidenceType {
    CITATION,
    SUPPLEMENT,
    EXPERIENCE
}

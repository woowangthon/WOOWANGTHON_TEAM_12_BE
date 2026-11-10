package com.woowangthon.backend.question;

/**
 * 초안 문장 종류(README 「값 목록」 draftSentenceKind) — 근거가 받치는 사실 · 「AI 제안 — 확인 필요」 생각 · 받치는 사실을 풀어 쓴 「AI 보탬 —
 * 확인 필요」(S-78) · 자료에 없는 장면을 그럴 법하게 쓴 「AI 가정」(ADR-014).
 */
public enum DraftSentenceKind {
    FACT,
    OPINION,
    ELABORATED,
    SUPPOSED
}

package com.woowangthon.backend.revision;

/** 답 스냅샷(계약 「스냅샷」) — 글 전체. 임시 글의 화면 상태 · 새 경험 후보는 남기지 않는다. */
public record AnswerSnapshot(String text) {
}

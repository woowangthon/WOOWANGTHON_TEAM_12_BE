package com.woowangthon.backend.experience;

/** 경험 STAR 정리 상태 — README 「값 목록」 starStatus (S-95). STALE 은 계약에 값만 남아 있고 만들지 않는다(ADR-019). */
public enum StarStatus {
    NONE, ANALYZING, READY, STALE, FAILED
}

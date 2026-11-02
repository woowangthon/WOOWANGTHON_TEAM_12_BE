package com.woowangthon.backend.job;

/** 분석 작업 상태 — 분석 중 → 완료 / 실패. */
public enum JobStatus {
    ANALYZING,
    COMPLETED,
    FAILED
}

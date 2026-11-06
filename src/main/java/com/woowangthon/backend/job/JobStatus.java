package com.woowangthon.backend.job;

/** 분석 작업 상태 — (차례 기다림 →) 분석 중 → 완료 / 실패. 차례 기다림은 자료 분석 작업(S-59)에만 있다. */
public enum JobStatus {
    QUEUED,
    ANALYZING,
    COMPLETED,
    FAILED
}

package com.woowangthon.backend.activity;

/**
 * 활동 분류(S-57) — 닫힌 목록 셋(계약 README 「값 목록」 activityCategory). 정리용이라 매칭 · 초안 · 근거 점검 · MCP 에 싣지 않는다.
 */
public enum ActivityCategory {
    /** 프로젝트 · 일 — 팀 · 개인 프로젝트 · 산학 · 연구실 · 인턴 · 실무 · 아르바이트 · 공모전 · 대회 */
    PROJECT,
    /** 공부 · 성장 — 수업 · 전공과목 · 스터디 · 외부교육 · 자격증 · 어학 */
    STUDY,
    /** 동아리 · 대외활동 — 동아리 · 학회 · 서포터즈 · 봉사 · 멘토링 · 해외 활동 */
    CLUB
}

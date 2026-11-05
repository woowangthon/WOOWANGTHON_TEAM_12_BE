package com.woowangthon.backend.question;

/** 지원 상태 — 닫힌 목록 5개(ETC docs/contracts/README.md 「값 목록」, SPEC S-36). 지원에 하나, 사용자가 붙인 꼬리표일 뿐이다. */
public enum ApplicationStatus {
    WRITING,
    SUBMITTED,
    PASSED,
    NOT_PASSED,
    UNKNOWN
}

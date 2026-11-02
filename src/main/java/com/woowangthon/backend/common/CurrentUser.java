package com.woowangthon.backend.common;

/** 지금 요청한 사용자. 로그인(R-02, BE#10)이 붙으면 이 자리를 인증 정보로 바꾼다. */
public final class CurrentUser {

    // SAMPLE(fixed-user): 로그인 전까지 모든 요청을 V1 마이그레이션이 넣은 사용자 1 로 본다
    public static final long FIXED_USER_ID = 1L;

    private CurrentUser() {
    }

    public static long id() {
        return FIXED_USER_ID;
    }
}

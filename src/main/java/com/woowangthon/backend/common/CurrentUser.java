package com.woowangthon.backend.common;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** 지금 요청한 사용자 — 로그인 때 세션에 넣은 사용자 ID(BE#10). 로그인하지 않았으면 401 UNAUTHENTICATED. */
public final class CurrentUser {

    public static final String SESSION_KEY = "userId";

    private CurrentUser() {
    }

    public static long id() {
        return find().orElseThrow(CurrentUser::unauthenticated);
    }

    public static Optional<Long> find() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object id = request == null ? null : request.getAttribute(SESSION_KEY, RequestAttributes.SCOPE_SESSION);
        return id instanceof Long userId ? Optional.of(userId) : Optional.empty();
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다");
    }
}

package com.woowangthon.backend.account;

import com.woowangthon.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api 아래는 로그인해야 쓴다(S-14) — 가입 · 로그인 · 로그아웃만 빼고. 로그인 안 했으면 401 UNAUTHENTICATED.
 * CORS 사전 요청(OPTIONS)은 쿠키 없이 오므로 통과시킨다.
 * 공개 프로필 읽기(S-65)는 그 경로 하나만 연다 — /api/public/** 처럼 넓히지 않는다. 이 자리엔 GET 만 둔다(share-api.md).
 */
@Configuration
public class LoginRequiredConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!CorsUtils.isPreFlightRequest(request) && CurrentUser.find().isEmpty()) {
                    throw CurrentUser.unauthenticated();
                }
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/signup", "/api/auth/login", "/api/auth/logout",
                "/api/public/profiles/*");
    }
}

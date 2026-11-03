package com.woowangthon.backend.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * FE(Vercel · 로컬)가 다른 도메인에서 API 를 부를 수 있게 허용할 주소. CORS_ALLOWED_ORIGINS 로 정한다.
 * 쿠키가 실린 요청(FE fetch credentials: 'include')의 응답도 FE 가 읽을 수 있게 한다 — 우리 로그인 세션 쿠키(BE#10)와,
 * Access 를 켜 둔 동안엔 Cloudflare Access 쿠키. 그래서 허용 주소는 우리 FE 만 정확히 넣는다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowCredentials(true);
    }
}

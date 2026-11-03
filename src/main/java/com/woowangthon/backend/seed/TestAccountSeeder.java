package com.woowangthon.backend.seed;

import com.woowangthon.backend.account.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * SAMPLE(test-account): TEST_ACCOUNT_PASSWORD 가 있으면 심사용 테스트 계정을 만든다(없으면 아무것도 안 한다).
 * 켤 때마다 비밀번호 · 한도를 설정값으로 맞춘다. 한도는 넉넉히(SPEC 미결정 6). 데이터는 미리 넣지 않는다 —
 * 진짜 로그인 · 분석 흐름을 그대로 탄다. 비밀번호는 README · 제출 폼에만 적는다(ETC docs/ai-scope.md §3).
 */
@Component
@Order(0)
public class TestAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestAccountSeeder.class);

    private final AuthService auth;
    private final String loginId;
    private final String password;
    private final int dailyLimit;

    public TestAccountSeeder(AuthService auth, @Value("${app.test-account.login-id}") String loginId,
            @Value("${app.test-account.password}") String password,
            @Value("${app.test-account.daily-limit}") int dailyLimit) {
        this.auth = auth;
        this.loginId = loginId;
        this.password = password;
        this.dailyLimit = dailyLimit;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            log.info("TEST_ACCOUNT_PASSWORD 가 비어 있어 테스트 계정을 만들지 않았습니다");
            return;
        }
        long id = auth.ensureAccount(loginId, password, dailyLimit);
        log.warn("테스트 계정 {}(사용자 {})을 맞췄습니다 — 하루 분석 {}회(SAMPLE)", loginId, id, dailyLimit);
    }
}

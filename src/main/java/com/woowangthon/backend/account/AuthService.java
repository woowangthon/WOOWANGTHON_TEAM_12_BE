package com.woowangthon.backend.account;

import com.woowangthon.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입 · 로그인 (ETC docs/contracts/auth-api.md). 비밀번호는 BCrypt 해시로만 저장한다. */
@Service
public class AuthService {

    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9_.-]{3,30}");
    static final int MIN_PASSWORD_LENGTH = 8;
    // BCrypt 는 72바이트까지만 본다 — 넘으면 뒤가 무시되니 받지 않는다
    private static final int MAX_PASSWORD_BYTES = 72;

    private final AppUserRepository users;
    private final UsageLimiter limiter;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(AppUserRepository users, UsageLimiter limiter) {
        this.users = users;
        this.limiter = limiter;
    }

    public record Credentials(String loginId, String password) {
    }

    public record Me(long id, String loginId, UsageLimiter.Usage dailyAnalysis) {
    }

    @Transactional
    public long signup(Credentials request) {
        String loginId = loginId(request.loginId());
        String password = password(request.password());
        if (users.existsByLoginId(loginId)) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_ID_TAKEN", "이미 있는 아이디입니다: " + loginId);
        }
        return users.save(new AppUser(loginId, encoder.encode(password), null)).getId();
    }

    @Transactional(readOnly = true)
    public long login(Credentials request) {
        String loginId = request.loginId() == null ? "" : request.loginId().trim().toLowerCase(Locale.ROOT);
        String password = request.password() == null ? "" : request.password();
        return users.findByLoginId(loginId)
                .filter(user -> encoder.matches(password, user.getPasswordHash()))
                .map(AppUser::getId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        "아이디 또는 비밀번호가 맞지 않습니다"));
    }

    @Transactional(readOnly = true)
    public Me me(long userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "로그인이 필요합니다"));
        return new Me(user.getId(), user.getLoginId(), limiter.today(userId));
    }

    /** 테스트 계정 — 있으면 비밀번호 · 한도를 설정값으로 맞추고, 없으면 만든다. */
    @Transactional
    public long ensureAccount(String rawLoginId, String rawPassword, int dailyLimit) {
        String loginId = loginId(rawLoginId);
        String hash = encoder.encode(password(rawPassword));
        AppUser user = users.findByLoginId(loginId).orElse(null);
        if (user == null) {
            return users.save(new AppUser(loginId, hash, dailyLimit)).getId();
        }
        user.reset(hash, dailyLimit);
        return user.getId();
    }

    private static String loginId(String value) {
        String loginId = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!LOGIN_ID.matcher(loginId).matches()) {
            throw ApiException.invalidRequest("loginId 는 영문 소문자 · 숫자 · _ . - 로 3 ~ 30자여야 합니다");
        }
        return loginId;
    }

    private static String password(String value) {
        if (value == null || value.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.invalidRequest("password 는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.invalidRequest("password 가 너무 깁니다(UTF-8 " + MAX_PASSWORD_BYTES + "바이트까지)");
        }
        return value;
    }
}

package com.woowangthon.backend.account;

import com.woowangthon.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ETC docs/contracts/auth-api.md — 가입 · 로그인 · 로그아웃 · 내 정보 (SPEC S-14 · S-15).
 * 로그인 상태는 서버 세션(쿠키 JSESSIONID)에 둔다. BE 를 다시 켜면 세션이 사라져 다시 로그인한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Me signup(@RequestBody AuthService.Credentials request, HttpServletRequest http) {
        long userId = service.signup(request);
        startSession(http, userId);
        return service.me(userId);
    }

    @PostMapping("/login")
    public AuthService.Me login(@RequestBody AuthService.Credentials request, HttpServletRequest http) {
        long userId = service.login(request);
        startSession(http, userId);
        return service.me(userId);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @GetMapping("/me")
    public AuthService.Me me() {
        return service.me(CurrentUser.id());
    }

    // 로그인 전 세션 ID 를 그대로 쓰지 않는다(세션 고정 공격)
    private static void startSession(HttpServletRequest http, long userId) {
        if (http.getSession(false) != null) {
            http.changeSessionId();
        }
        http.getSession(true).setAttribute(CurrentUser.SESSION_KEY, userId);
    }
}

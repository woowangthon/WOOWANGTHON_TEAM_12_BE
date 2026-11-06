package com.woowangthon.backend.share;

import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.share.PublicProfileResponses.PublicProfileView;
import com.woowangthon.backend.share.PublicProfileResponses.Settings;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/share-api.md — 내 공개 설정 · 링크 켜기 · 끄기 · 공개 읽기 (SPEC S-65 · S-66). */
@RestController
public class PublicProfileController {

    private final PublicProfileService service;

    public PublicProfileController(PublicProfileService service) {
        this.service = service;
    }

    @GetMapping("/api/public-profile")
    public Settings settings() {
        return service.settings();
    }

    @PatchMapping("/api/public-profile")
    public Settings rename(@RequestBody(required = false) Map<String, Object> body) {
        return service.rename(new PatchBody(body));
    }

    @PostMapping("/api/public-profile/link")
    @ResponseStatus(HttpStatus.CREATED)
    public Settings enableLink() {
        return service.enableLink();
    }

    @DeleteMapping("/api/public-profile/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableLink() {
        service.disableLink();
    }

    /** 로그인 없이 열리는 유일한 조회(LoginRequiredConfig). 404 도 어디에도 남지 않게 헤더를 먼저 단다 — 끈 주소는 바로 막혀야 한다(S-66). */
    @GetMapping("/api/public/profiles/{token}")
    public PublicProfileView read(@PathVariable String token, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return service.read(token);
    }
}

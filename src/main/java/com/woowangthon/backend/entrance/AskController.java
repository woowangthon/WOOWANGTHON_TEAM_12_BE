package com.woowangthon.backend.entrance;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/entrance-api.md 「POST /api/ask」 — 꺼내기(SPEC F-10 S-29). 로그인 필요(LoginRequiredConfig). */
@RestController
public class AskController {

    private final AskService service;

    public AskController(AskService service) {
        this.service = service;
    }

    @PostMapping("/api/ask")
    public AskService.AskResult ask(@RequestBody(required = false) EntranceService.TextRequest request) {
        return service.ask(request);
    }
}

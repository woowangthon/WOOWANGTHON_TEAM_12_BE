package com.woowangthon.backend.entrance;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/entrance-api.md — 홈 입력 가르기 · 지원 나누기 (SPEC F-10 S-28 · S-30). 로그인 필요(LoginRequiredConfig). */
@RestController
@RequestMapping("/api/entrance")
public class EntranceController {

    private final EntranceService service;

    public EntranceController(EntranceService service) {
        this.service = service;
    }

    @PostMapping("/route")
    public EntranceService.RouteResult route(@RequestBody EntranceService.TextRequest request) {
        return service.route(request);
    }

    @PostMapping("/parse-application")
    public EntranceService.ParseApplicationResult parseApplication(
            @RequestBody EntranceService.TextRequest request) {
        return service.parseApplication(request);
    }
}

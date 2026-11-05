package com.woowangthon.backend.question;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/question-api.md 「답변 초안」 — 만들기 · 다시 만들기 (SPEC S-27). 결과는 문항 응답의 draft 로 온다. */
@RestController
public class DraftController {

    private final DraftService service;
    private final DraftRunner runner;

    public DraftController(DraftService service, DraftRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @PostMapping("/api/questions/{questionId}/draft")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DraftService.DraftView start(@PathVariable long questionId,
            @RequestBody(required = false) DraftService.StartRequest request) {
        DraftService.DraftView draft = service.start(questionId, request);
        runner.run(questionId);
        return draft;
    }
}

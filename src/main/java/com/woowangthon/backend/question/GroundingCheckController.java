package com.woowangthon.backend.question;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/question-api.md 「근거 점검」 — 시작 · 결과 (SPEC S-45). */
@RestController
@RequestMapping("/api/questions/{questionId}/grounding-check")
public class GroundingCheckController {

    private final GroundingCheckService service;
    private final GroundingRunner runner;

    public GroundingCheckController(GroundingCheckService service, GroundingRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GroundingCheckService.Check start(@PathVariable long questionId,
            @RequestBody(required = false) GroundingCheckService.StartRequest request) {
        GroundingCheckService.Check check = service.start(questionId, request);
        runner.run(questionId);
        return check;
    }

    @GetMapping
    public GroundingCheckService.Result get(@PathVariable long questionId) {
        return service.get(questionId);
    }
}

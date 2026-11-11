package com.woowangthon.backend.question;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/question-api.md — 지원 넣기 · 문항 더하기 · 목록 · 지원 하나 · 종류 · 직무 · 상태 · 낸 달 바꾸기 (SPEC S-18 · S-21 · S-42 · S-36 · S-37). */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService service;
    private final MatchRunner runner;
    private final PostingMatchRunner postingRunner;

    public ApplicationController(ApplicationService service, MatchRunner runner, PostingMatchRunner postingRunner) {
        this.service = service;
        this.runner = runner;
        this.postingRunner = postingRunner;
    }

    // 문항마다 추천 작업 하나 + 공고 요건 줄이 있으면 공고 기준 경험 찾기 하나 — 비동기 실행기의 스레드 수만큼 동시에 돈다
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuestionResponses.ApplicationSubmitted submit(@RequestBody ApplicationService.ApplicationRequest request) {
        QuestionResponses.ApplicationSubmitted submitted = service.submit(request);
        submitted.questionIds().forEach(runner::run);
        if (submitted.postingMatch()) {
            postingRunner.run(submitted.applicationId());
        }
        return submitted;
    }

    // 다시 시도 · 경험 다시 찾기(S-93) — 저장 트랜잭션이 끝난 뒤 작업을 돌린다
    @PostMapping("/{applicationId}/posting-match/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuestionResponses.ApplicationResponse retryPostingMatch(@PathVariable long applicationId) {
        QuestionResponses.ApplicationResponse application = service.retryPostingMatch(applicationId);
        postingRunner.run(applicationId);
        return application;
    }

    @PostMapping("/{applicationId}/questions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuestionResponses.Submitted addQuestion(@PathVariable long applicationId,
            @RequestBody ApplicationService.QuestionText request) {
        QuestionResponses.Submitted submitted = service.addQuestion(applicationId, request);
        runner.run(submitted.questionId());
        return submitted;
    }

    @GetMapping
    public QuestionResponses.Applications list() {
        return service.list();
    }

    @GetMapping("/{applicationId}")
    public QuestionResponses.ApplicationResponse get(@PathVariable long applicationId) {
        return service.get(applicationId);
    }

    @DeleteMapping("/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long applicationId) {
        service.delete(applicationId);
    }

    @PatchMapping("/{applicationId}")
    public QuestionResponses.ApplicationResponse update(@PathVariable long applicationId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.update(applicationId, new PatchBody(body));
    }
}

package com.woowangthon.backend.question;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/question-api.md — 문항 넣기 · 목록 · 문항 하나 · 다시 시도 · 유형 고치기 (SPEC S-18 ~ S-21 · S-41). */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService service;
    private final MatchRunner runner;

    public QuestionController(QuestionService service, MatchRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuestionResponses.Submitted submit(@RequestBody QuestionService.QuestionRequest request) {
        QuestionResponses.Submitted submitted = service.submit(request);
        runner.run(submitted.questionId());
        return submitted;
    }

    @GetMapping
    public QuestionResponses.Questions list() {
        return service.list();
    }

    @GetMapping("/{questionId}")
    public QuestionResponses.Detail get(@PathVariable long questionId) {
        return service.get(questionId);
    }

    @PatchMapping("/{questionId}")
    public QuestionResponses.Detail update(@PathVariable long questionId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.update(questionId, new PatchBody(body));
    }

    @PostMapping("/{questionId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuestionResponses.Detail retry(@PathVariable long questionId) {
        QuestionResponses.Detail question = service.retry(questionId);
        runner.run(questionId);
        return question;
    }
}

package com.woowangthon.backend.question;

import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.experience.ExperienceResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/question-api.md 「확정 답변」 — 확정 · 후보 찾기 · 경험으로 저장 · 저장 안 함 (SPEC S-48). */
@RestController
public class AnswerController {

    private final AnswerService service;
    private final NewFactsRunner runner;

    public AnswerController(AnswerService service, NewFactsRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    /** 비슷한 확정 답변(S-43) — 참고 목록. AI 를 부르지 않고 분석 횟수를 쓰지 않는다. */
    @GetMapping("/api/questions/{questionId}/similar-answers")
    public AnswerService.SimilarAnswers similarAnswers(@PathVariable long questionId) {
        return service.similar(questionId);
    }

    @PutMapping("/api/questions/{questionId}/answer")
    public AnswerService.Result confirm(@PathVariable long questionId,
            @RequestBody(required = false) AnswerService.ConfirmRequest request) {
        return service.confirm(questionId, request);
    }

    @PostMapping("/api/questions/{questionId}/answer/new-facts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnswerService.Result findNewFacts(@PathVariable long questionId) {
        AnswerService.Result result = service.startFinding(questionId);
        runner.run(questionId);
        return result;
    }

    @PostMapping("/api/new-fact-candidates/{candidateId}/save")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperienceResponse save(@PathVariable long candidateId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.save(candidateId, new PatchBody(body));
    }

    @PostMapping("/api/new-fact-candidates/{candidateId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable long candidateId) {
        service.dismiss(candidateId);
    }
}

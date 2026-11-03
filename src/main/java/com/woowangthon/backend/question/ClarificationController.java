package com.woowangthon.backend.question;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/question-api.md 「되묻기에 답하기」 (SPEC S-23). 건너뛰기는 FE 가 아무것도 보내지 않는 것이다. */
@RestController
@RequestMapping("/api/clarifications")
public class ClarificationController {

    private final ClarificationService service;

    public ClarificationController(ClarificationService service) {
        this.service = service;
    }

    @PostMapping("/{clarificationId}/answer")
    @ResponseStatus(HttpStatus.CREATED)
    public ClarificationService.Answered answer(@PathVariable long clarificationId,
            @RequestBody ClarificationService.AnswerRequest request) {
        return service.answer(clarificationId, request);
    }
}

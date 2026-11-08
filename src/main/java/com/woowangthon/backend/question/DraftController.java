package com.woowangthon.backend.question;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** question-api.md 「POST /api/draft-sentences/{sentenceId}/confirm」 — 카드의 「맞아요」(S-79). */
    @PostMapping("/api/draft-sentences/{sentenceId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable long sentenceId) {
        service.confirm(sentenceId);
    }

    /** question-api.md 「PUT /api/draft-sentences/{sentenceId}/removed」 — 카드의 「빼기」 · 「다시 넣기」(S-79). */
    @PutMapping("/api/draft-sentences/{sentenceId}/removed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setRemoved(@PathVariable long sentenceId, @RequestBody(required = false) Map<String, Object> body) {
        service.setRemoved(sentenceId, new PatchBody(body));
    }
}

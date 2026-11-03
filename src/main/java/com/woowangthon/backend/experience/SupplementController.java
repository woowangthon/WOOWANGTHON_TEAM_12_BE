package com.woowangthon.backend.experience;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md 「보완 고치기 · 지우기」 (SPEC S-24). 보완이 생기는 곳은 되묻기에 답하기(ClarificationController). */
@RestController
@RequestMapping("/api/supplements")
public class SupplementController {

    private final SupplementService service;

    public SupplementController(SupplementService service) {
        this.service = service;
    }

    @PatchMapping("/{supplementId}")
    public ExperienceResponse.SupplementResponse update(@PathVariable long supplementId,
            @RequestBody SupplementService.AnswerRequest request) {
        return service.update(supplementId, request);
    }

    @DeleteMapping("/{supplementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long supplementId) {
        service.delete(supplementId);
    }
}

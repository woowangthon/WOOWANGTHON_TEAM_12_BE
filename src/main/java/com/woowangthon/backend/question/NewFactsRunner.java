package com.woowangthon.backend.question;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.NewFactsRequest;
import com.woowangthon.backend.ai.NewFactsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 새 경험 후보 찾기 하나를 뒤에서 돌린다: 싣기 → AI 서버 /new-facts → 후보 저장 → 완료, 어디서든 실패하면 실패 + 이유.
 * 실패를 정해진 후보로 대신 채우지 않는다(대회 규칙 2) — GroundingRunner 와 같다.
 */
@Component
public class NewFactsRunner {

    private static final Logger log = LoggerFactory.getLogger(NewFactsRunner.class);

    private final AnswerService service;
    private final AiClient ai;

    public NewFactsRunner(AnswerService service, AiClient ai) {
        this.service = service;
        this.ai = ai;
    }

    /** 찾기 시작 트랜잭션이 끝난 뒤에 부른다(AnswerController). */
    @Async
    public void run(long questionId) {
        try {
            NewFactsRequest request = service.request(questionId);
            NewFactsResponse result = ai.newFacts(request);
            service.complete(questionId, request, result);
            log.info("새 경험 후보 찾기(문항 {}) 완료 — 경험 {}개, 뺄 문장 {}개, 후보 {}개, 모델 {}", questionId,
                    request.experiences().size(), request.excludedSentences().size(),
                    result.candidates() == null ? 0 : result.candidates().size(), result.model());
        } catch (AiCallException e) {
            log.warn("새 경험 후보 찾기(문항 {}) 실패 — {} {}", questionId, e.code(), e.getMessage());
            fail(questionId, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("새 경험 후보 찾기(문항 {}) 실패 — 내부 오류", questionId, e);
            fail(questionId, "INTERNAL_ERROR", "새 경험 후보를 저장하지 못했습니다");
        }
    }

    private void fail(long questionId, String code, String message) {
        try {
            service.fail(questionId, code, message);
        } catch (RuntimeException e) {
            log.error("문항 {} 의 새 경험 후보 찾기를 실패로 남기지 못했습니다", questionId, e);
        }
    }
}

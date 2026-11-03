package com.woowangthon.backend.question;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 추천 작업 하나를 뒤에서 돌린다: 후보 싣기 → AI 서버 매칭 → 결과 저장 → 완료, 어디서든 실패하면 실패 + 이유.
 * 실패를 정해진 결과로 대신 채우지 않는다(대회 규칙 2).
 */
@Component
public class MatchRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchRunner.class);

    private final QuestionService service;
    private final AiClient ai;

    public MatchRunner(QuestionService service, AiClient ai) {
        this.service = service;
        this.ai = ai;
    }

    /** 문항 저장 트랜잭션이 끝난 뒤에 부른다(QuestionController). */
    @Async
    public void run(long questionId) {
        try {
            MatchRequest request = service.matchRequest(questionId);
            MatchResponse result = ai.match(request);
            service.complete(questionId, request, result);
            log.info("추천 작업(문항 {}) 완료 — 후보 {}개, 추천 {}개, 모델 {}", questionId, request.experiences().size(),
                    result.recommendations() == null ? 0 : result.recommendations().size(), result.model());
        } catch (AiCallException e) {
            log.warn("추천 작업(문항 {}) 실패 — {} {}", questionId, e.code(), e.getMessage());
            fail(questionId, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("추천 작업(문항 {}) 실패 — 내부 오류", questionId, e);
            fail(questionId, "INTERNAL_ERROR", "추천 결과를 저장하지 못했습니다");
        }
    }

    private void fail(long questionId, String code, String message) {
        try {
            service.fail(questionId, code, message);
        } catch (RuntimeException e) {
            log.error("문항 {} 을(를) 실패로 남기지 못했습니다", questionId, e);
        }
    }
}

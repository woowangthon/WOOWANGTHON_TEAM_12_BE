package com.woowangthon.backend.question;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.DraftRequest;
import com.woowangthon.backend.ai.DraftResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 답변 초안 하나를 뒤에서 돌린다: 고른 경험 싣기 → AI 서버 초안 → 결과 저장 → 완료, 어디서든 실패하면 실패 + 이유.
 * 실패를 정해진 문장으로 대신 채우지 않는다(대회 규칙 2) — MatchRunner 와 같다.
 */
@Component
public class DraftRunner {

    private static final Logger log = LoggerFactory.getLogger(DraftRunner.class);

    private final DraftService service;
    private final AiClient ai;

    public DraftRunner(DraftService service, AiClient ai) {
        this.service = service;
        this.ai = ai;
    }

    /** 초안 시작 트랜잭션이 끝난 뒤에 부른다(DraftController). */
    @Async
    public void run(long questionId) {
        try {
            DraftRequest request = service.request(questionId);
            DraftResponse result = ai.draft(request);
            service.complete(questionId, request, result);
            log.info("답변 초안(문항 {}) 완료 — 경험 {}개, 문장 {}개, 모델 {}", questionId, request.experiences().size(),
                    result.sentences() == null ? 0 : result.sentences().size(), result.model());
        } catch (AiCallException e) {
            log.warn("답변 초안(문항 {}) 실패 — {} {}", questionId, e.code(), e.getMessage());
            fail(questionId, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("답변 초안(문항 {}) 실패 — 내부 오류", questionId, e);
            fail(questionId, "INTERNAL_ERROR", "답변 초안을 저장하지 못했습니다");
        }
    }

    private void fail(long questionId, String code, String message) {
        try {
            service.fail(questionId, code, message);
        } catch (RuntimeException e) {
            log.error("문항 {} 의 답변 초안을 실패로 남기지 못했습니다", questionId, e);
        }
    }
}

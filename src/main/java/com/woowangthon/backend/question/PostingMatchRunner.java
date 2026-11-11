package com.woowangthon.backend.question;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.PostingMatchRequest;
import com.woowangthon.backend.ai.PostingMatchResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 공고 기준 경험 찾기(S-93) 하나를 뒤에서 돌린다: 줄 · 경험 싣기 → AI 서버 /posting-match → 결과 저장 → 완료, 어디서든 실패하면 실패 + 이유.
 * MatchRunner 와 같은 방식이다. 실패를 정해진 판정으로 대신 채우지 않는다(대회 규칙 2).
 */
@Component
public class PostingMatchRunner {

    private static final Logger log = LoggerFactory.getLogger(PostingMatchRunner.class);

    private final PostingMatchService service;
    private final AiClient ai;

    public PostingMatchRunner(PostingMatchService service, AiClient ai) {
        this.service = service;
        this.ai = ai;
    }

    /** 지원 저장 · 다시 시도 트랜잭션이 끝난 뒤에 부른다(ApplicationController · 외부 AI 지원 만들기). */
    @Async
    public void run(long applicationId) {
        try {
            Optional<PostingMatchRequest> request = service.request(applicationId);
            if (request.isEmpty()) {
                log.info("공고 기준 경험 찾기(지원 {}) 건너뜀 — 그새 지워졌거나 돌 차례가 아닙니다", applicationId);
                return;
            }
            PostingMatchResponse result = ai.postingMatch(request.get());
            service.complete(applicationId, request.get(), result);
            log.info("공고 기준 경험 찾기(지원 {}) 완료 — 줄 {}개, 후보 {}개, 모델 {}", applicationId,
                    request.get().lines().size(), request.get().experiences().size(), result.model());
        } catch (AiCallException e) {
            log.warn("공고 기준 경험 찾기(지원 {}) 실패 — {} {}", applicationId, e.code(), e.getMessage());
            fail(applicationId, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("공고 기준 경험 찾기(지원 {}) 실패 — 내부 오류", applicationId, e);
            fail(applicationId, "INTERNAL_ERROR", "공고 기준 경험 찾기 결과를 저장하지 못했습니다");
        }
    }

    private void fail(long applicationId, String code, String message) {
        try {
            service.fail(applicationId, code, message);
        } catch (RuntimeException e) {
            log.error("지원 {} 의 공고 기준 경험 찾기를 실패로 남기지 못했습니다", applicationId, e);
        }
    }
}

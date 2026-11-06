package com.woowangthon.backend.job;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.ExtractRequest;
import com.woowangthon.backend.ai.ExtractResponse;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 분석 작업 하나를 뒤에서 돌린다: AI 서버 호출 → 결과 저장 → 완료, 어디서든 실패하면 실패 + 이유.
 * 실패를 정해진 결과로 대신 채우지 않는다(대회 규칙 2).
 */
@Component
public class ExtractionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRunner.class);

    private final AnalysisService service;
    private final AiClient ai;

    public ExtractionRunner(AnalysisService service, AiClient ai) {
        this.service = service;
        this.ai = ai;
    }

    /** 원본 저장 트랜잭션이 끝난 뒤에 부른다(JobController 등). 끝나면 이 사용자의 다음 QUEUED 를 이어 돌린다(S-59). */
    @Async
    public void run(long jobId) {
        Optional<Long> next = Optional.of(jobId);
        while (next.isPresent()) {
            analyze(next.get());
            next = startNext(next.get());
        }
    }

    // 실패해도 다음 작업은 돈다. 여기서 못 넘기면 다음 작업이 끝날 때나 서버가 다시 켜질 때 이어진다
    private Optional<Long> startNext(long finishedJobId) {
        try {
            return service.startNextAfter(finishedJobId);
        } catch (RuntimeException e) {
            log.error("분석 작업 {} 다음 차례를 시작하지 못했습니다", finishedJobId, e);
            return Optional.empty();
        }
    }

    private void analyze(long jobId) {
        try {
            ExtractRequest request = service.extractRequest(jobId);
            ExtractResponse result = ai.extract(request);
            List<Long> activityIds = service.complete(jobId, request, result);
            log.info("분석 작업 {} 완료 — 활동 {}개, 모델 {}", jobId, activityIds.size(), result.model());
        } catch (AiCallException e) {
            log.warn("분석 작업 {} 실패 — {} {}", jobId, e.code(), e.getMessage());
            fail(jobId, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("분석 작업 {} 실패 — 내부 오류", jobId, e);
            fail(jobId, "INTERNAL_ERROR", "분석 결과를 저장하지 못했습니다");
        }
    }

    private void fail(long jobId, String code, String message) {
        try {
            service.fail(jobId, code, message);
        } catch (RuntimeException e) {
            log.error("분석 작업 {} 을(를) 실패로 남기지 못했습니다", jobId, e);
        }
    }
}

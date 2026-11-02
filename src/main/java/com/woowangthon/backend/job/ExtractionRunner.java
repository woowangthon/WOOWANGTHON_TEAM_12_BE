package com.woowangthon.backend.job;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.ExtractResponse;
import java.util.List;
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

    /** 원본 저장 트랜잭션이 끝난 뒤에 부른다(SourceController · JobController). */
    @Async
    public void run(long jobId) {
        try {
            ExtractResponse result = ai.extract(service.extractRequest(jobId));
            List<Long> activityIds = service.complete(jobId, result);
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

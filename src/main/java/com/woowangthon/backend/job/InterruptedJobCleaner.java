package com.woowangthon.backend.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 서버가 분석 도중 꺼지면 작업이 「분석 중」에 멈춰 FE 가 끝없이 기다린다. 시작할 때 그런 작업을
 * 실패로 돌려 「다시 시도」할 수 있게 하고, 남은 QUEUED 는 차례대로 이어 돌린다(S-59).
 * 서버 한 대만 띄우는 지금 구성(ADR-004)을 전제로 한다.
 */
@Component
public class InterruptedJobCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedJobCleaner.class);

    private final AnalysisService service;
    private final ExtractionRunner runner;

    public InterruptedJobCleaner(AnalysisService service, ExtractionRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        int interrupted = service.failInterrupted();
        if (interrupted > 0) {
            log.warn("분석 중에 끊긴 작업 {}개를 실패로 돌렸습니다", interrupted);
        }
        for (long userId : service.usersWithQueued()) {
            service.startNext(userId).ifPresent(runner::run);
        }
    }
}

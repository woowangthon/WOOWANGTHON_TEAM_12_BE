package com.woowangthon.backend.job;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 분석 도중 꺼지면 작업이 「분석 중」에 멈춰 FE 가 끝없이 기다린다. 시작할 때 그런 작업을
 * 실패로 돌려 「다시 시도」할 수 있게 한다. 서버 한 대만 띄우는 지금 구성(ADR-004)을 전제로 한다.
 */
@Component
public class InterruptedJobCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedJobCleaner.class);

    private final AnalysisJobRepository jobs;

    public InterruptedJobCleaner(AnalysisJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AnalysisJob> interrupted = jobs.findByStatus(JobStatus.ANALYZING);
        interrupted.forEach(job -> job.fail("INTERNAL_ERROR", "서버가 다시 시작되어 분석이 끊겼습니다"));
        if (!interrupted.isEmpty()) {
            log.warn("분석 중에 끊긴 작업 {}개를 실패로 돌렸습니다", interrupted.size());
        }
    }
}

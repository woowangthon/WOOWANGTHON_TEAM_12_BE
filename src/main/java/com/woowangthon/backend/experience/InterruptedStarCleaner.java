package com.woowangthon.backend.experience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 서버가 STAR 정리 도중 꺼지면 경험이 「정리 중」에 멈춘다. 시작할 때 실패로 돌려 「다시 시도」할 수 있게 한다(서버 한 대 — ADR-004). */
@Component
public class InterruptedStarCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedStarCleaner.class);

    private final StarService service;

    public InterruptedStarCleaner(StarService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        int interrupted = service.failInterrupted();
        if (interrupted > 0) {
            log.warn("STAR 정리 중에 끊긴 경험 {}개를 실패로 돌렸습니다", interrupted);
        }
    }
}

package com.woowangthon.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// 분석 작업(ExtractionRunner)을 요청 스레드 밖에서 돌린다 · 비어 있는 STAR 를 주기적으로 정리한다(StarSweeper)
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}

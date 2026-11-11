package com.woowangthon.backend.experience;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.StarRequest;
import com.woowangthon.backend.ai.StarResponse;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STAR 정리(S-95)를 뒤에서 돌린다: 묶음마다 AI 서버 /star → 칸 저장, 어디서든 실패하면 그 묶음의 경험만 FAILED.
 * 실패를 정해진 칸으로 대신 채우지 않는다(대회 규칙 2). 진행은 활동 세부의 star.status 로 본다 — 작업 ID 를 따로 두지 않는다.
 * /star 는 분석 횟수 밖의 유료 호출이라 어느 길로 왔든(새 자료 분석 뒤 · 다시 시도 · StarSweeper) 동시에 MAX_CONCURRENT 개까지만 부른다(BE#180).
 */
@Component
public class StarRunner {

    private static final Logger log = LoggerFactory.getLogger(StarRunner.class);

    public static final int MAX_CONCURRENT = 2;

    private final StarService service;
    private final AiClient ai;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT,
            Thread.ofPlatform().name("star-", 1).factory());
    // 맡았지만 아직 끝나지 않은 묶음 수(줄 선 것 + 도는 것) — StarSweeper 가 이만큼은 더 맡기지 않는다
    private final AtomicInteger pending = new AtomicInteger();

    public StarRunner(StarService service, AiClient ai) {
        this.service = service;
        this.ai = ai;
    }

    /** 경험들을 정리 중으로 둔 트랜잭션이 끝난 뒤에 부른다. 묶음은 줄을 서서 MAX_CONCURRENT 개씩 돈다. */
    public void run(List<List<Long>> groups) {
        for (List<Long> group : groups) {
            pending.incrementAndGet();
            executor.execute(() -> {
                try {
                    runGroup(group);
                } finally {
                    pending.decrementAndGet();
                }
            });
        }
    }

    public int pending() {
        return pending.get();
    }

    @PreDestroy
    void stop() {
        // 줄 선 묶음은 버린다 — 정리 중으로 남은 경험은 다음에 켤 때 InterruptedStarCleaner 가 FAILED 로 돌린다
        executor.shutdownNow();
    }

    private void runGroup(List<Long> experienceIds) {
        try {
            Optional<StarRequest> request = service.request(experienceIds);
            if (request.isEmpty()) {
                log.info("STAR 정리 건너뜀 — 경험 {} 이(가) 그새 지워졌거나 정리 중이 아닙니다", experienceIds);
                return;
            }
            StarResponse result = ai.star(request.get());
            service.complete(request.get(), result);
            log.info("STAR 정리 완료 — 경험 {}개, 모델 {}", request.get().experiences().size(), result.model());
        } catch (AiCallException e) {
            log.warn("STAR 정리 실패(경험 {}) — {} {}", experienceIds, e.code(), e.getMessage());
            fail(experienceIds);
        } catch (RuntimeException e) {
            log.error("STAR 정리 실패(경험 {}) — 내부 오류", experienceIds, e);
            fail(experienceIds);
        }
    }

    private void fail(List<Long> experienceIds) {
        try {
            service.fail(experienceIds);
        } catch (RuntimeException e) {
            log.error("경험 {} 의 STAR 정리를 실패로 남기지 못했습니다", experienceIds, e);
        }
    }
}

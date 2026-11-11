package com.woowangthon.backend.experience;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 비어 있는 STAR 를 BE 가 알아서 정리한다(BE#180 · ADR-019 · experience-api 「언제 정리되나」). STAR 기능 전에 만든 경험과
 * 자료 분석 밖에서 생긴 경험(직접 기록 · 합치기 등)이 NONE 으로 남는다 — 주기마다 그런 활동을 StarRunner 의 빈자리만큼 맡겨
 * 배포 뒤 쌓인 것은 몰아서, 이후 생긴 것은 그때 정리된다. 분석 횟수를 세지 않는다. 한 번 정리한 경험은 다시 정리하지 않는다.
 */
@Component
public class StarSweeper {

    private static final Logger log = LoggerFactory.getLogger(StarSweeper.class);

    // 막 만든 경험은 사용자가 바로 고칠 수 있으니 조금 두고 정리한다
    static final Duration SETTLE = Duration.ofSeconds(30);

    private final StarService service;
    private final StarRunner runner;
    private final boolean enabled;
    private final long maxBacklog;
    private volatile Boolean allowed;

    public StarSweeper(StarService service, StarRunner runner,
            @Value("${app.star.auto-sort.enabled}") boolean enabled,
            @Value("${app.star.auto-sort.max-backlog}") long maxBacklog) {
        this.service = service;
        this.runner = runner;
        this.enabled = enabled;
        this.maxBacklog = maxBacklog;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 10_000)
    void tick() {
        if (!enabled || !allowed()) {
            return;
        }
        try {
            sweep(OffsetDateTime.now().minus(SETTLE));
        } catch (RuntimeException e) {
            log.error("STAR 알아서 정리 — 이번 주기를 건너뜁니다", e);
        }
    }

    /**
     * before 전에 생긴 NONE 경험이 남은 활동을 StarRunner 빈자리만큼 정리 중으로 두고 맡긴다. 맡긴 활동 수를 돌려준다.
     * 한 번에 빈자리만큼만 맡겨 서버가 꺼져도 FAILED 로 남는 활동이 적다.
     */
    public int sweep(OffsetDateTime before) {
        int free = StarRunner.MAX_CONCURRENT - runner.pending();
        if (free <= 0) {
            return 0;
        }
        int started = 0;
        for (long activityId : service.activitiesToSort(before, free)) {
            List<List<Long>> groups = service.startUnsorted(activityId);
            if (!groups.isEmpty()) {
                runner.run(groups);
                started++;
            }
        }
        if (started > 0) {
            log.info("STAR 알아서 정리 — 활동 {}개를 맡겼습니다", started);
        }
        return started;
    }

    // 켜진 뒤 처음 한 번 센다 — 너무 많으면 유료 호출이 몰리니 시작하지 않는다(유료 API 한도는 사람만 — AGENTS.md)
    private boolean allowed() {
        if (allowed == null) {
            long backlog = service.countActivitiesToSort();
            allowed = backlog <= maxBacklog;
            if (allowed) {
                log.info("STAR 알아서 정리 — 정리 안 한 경험이 남은 활동 {}개", backlog);
            } else {
                log.warn("STAR 알아서 정리를 시작하지 않습니다 — 정리 안 한 경험이 남은 활동 {}개가 상한 {}개를 넘습니다. "
                        + "PM 에게 묻고 STAR_AUTO_SORT_MAX_BACKLOG 를 올려 다시 켜세요", backlog, maxBacklog);
            }
        }
        return allowed;
    }
}

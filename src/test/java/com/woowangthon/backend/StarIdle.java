package com.woowangthon.backend;

import com.woowangthon.backend.experience.StarRunner;

/**
 * 자료 분석이 끝나면 STAR 정리가 StarRunner 줄에서 뒤늦게 돈다(동시 2개 — BE#180). 테스트가 표를 비우기 전에 그 줄이 빌 때까지 기다린다 —
 * 겹치면 truncate 와 정리 저장이 서로 잠금을 기다려 교착된다.
 */
final class StarIdle {

    private StarIdle() {
    }

    static void await(StarRunner runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (runner.pending() > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("STAR 정리가 15초 안에 끝나지 않았습니다");
            }
            Thread.sleep(20);
        }
    }
}

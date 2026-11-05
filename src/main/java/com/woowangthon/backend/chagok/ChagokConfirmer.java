package com.woowangthon.backend.chagok;

import org.springframework.stereotype.Component;

/**
 * 확인하고 실행 — 트랜잭션 밖에서 감싼다. 실패하면 쓴 것을 되돌린 뒤 따로 FAILED 를 남겨야 해서 ChagokService 와 나눴다.
 * 분석은 쓰기가 커밋된 뒤 시작한다.
 */
@Component
public class ChagokConfirmer {

    private final ChagokService service;

    public ChagokConfirmer(ChagokService service) {
        this.service = service;
    }

    public ChagokService.Run confirm(long runId) {
        ChagokService.Executed executed;
        try {
            executed = service.execute(runId);
        } catch (ChagokService.ConfirmFailed e) {
            return service.failConfirm(runId, e.code(), e.getMessage());
        }
        executed.afterCommit().run();
        return executed.run();
    }
}

package com.woowangthon.backend.chagok;

import com.woowangthon.backend.mcp.McpConnectionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 일 도중 꺼지면 RUNNING 에 멈춰 그 사용자는 새 일을 못 시작한다(409). 시작할 때 실패로 돌리고 남은 토큰을 지운다.
 * 서버 한 대만 띄우는 지금 구성(ADR-004)을 전제로 한다.
 */
@Component
public class InterruptedRunCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedRunCleaner.class);

    private final ChagokRunRepository runs;
    private final McpConnectionService connections;

    public InterruptedRunCleaner(ChagokRunRepository runs, McpConnectionService connections) {
        this.runs = runs;
        this.connections = connections;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ChagokRun> interrupted = runs.findByStatus(ChagokStatus.RUNNING);
        interrupted.forEach(run -> run.fail("INTERNAL_ERROR", "서버가 다시 시작되어 일이 끊겼습니다"));
        connections.revokeAllChagok();
        if (!interrupted.isEmpty()) {
            log.warn("진행 중에 끊긴 차곡이 일 {}개를 실패로 돌렸습니다", interrupted.size());
        }
    }
}

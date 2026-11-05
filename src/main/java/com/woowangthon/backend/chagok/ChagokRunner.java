package com.woowangthon.backend.chagok;

import com.woowangthon.backend.ai.AgentStream;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.mcp.McpConnectionService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 일 하나를 뒤에서 돌린다: AI /agent 스트림을 열어 이벤트마다 단계를 쌓고, 끝나면 토큰을 지운다.
 * 실패를 정해진 결과로 대신 채우지 않는다(대회 규칙 2).
 */
@Component
public class ChagokRunner {

    private static final Logger log = LoggerFactory.getLogger(ChagokRunner.class);

    private final ChagokService service;
    private final McpConnectionService connections;
    private final AiClient ai;
    private final long timeoutSeconds;
    // 읽는 중인 스트림 — 그만두기 · 제한 시간에 다른 스레드가 닫는다
    private final Map<Long, AgentStream> open = new ConcurrentHashMap<>();

    public ChagokRunner(ChagokService service, McpConnectionService connections, AiClient ai,
            @Value("${app.chagok.timeout-seconds}") long timeoutSeconds) {
        this.service = service;
        this.connections = connections;
        this.ai = ai;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 일 저장 트랜잭션이 끝난 뒤에 부른다(ChagokController). */
    @Async
    public void run(ChagokService.Launch launch) {
        long runId = launch.runId();
        AtomicBoolean timedOut = new AtomicBoolean();
        CompletableFuture<Void> deadline = new CompletableFuture<Void>().orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        deadline.exceptionally(e -> {
            timedOut.set(true);
            stop(runId);
            return null;
        });
        try {
            read(launch, timedOut);
        } catch (AiCallException e) {
            String code = timedOut.get() ? "AI_TIMEOUT" : e.code();
            String message = timedOut.get() ? timeoutMessage() : e.getMessage();
            log.warn("차곡이 일 {} 실패 — {} {}", runId, code, message);
            fail(runId, code, message);
        } catch (RuntimeException e) {
            log.error("차곡이 일 {} 실패 — 내부 오류", runId, e);
            fail(runId, "INTERNAL_ERROR", "단계를 저장하지 못했습니다");
        } finally {
            deadline.complete(null);
            revoke(launch.tokenId());
        }
    }

    /** 읽는 중인 스트림을 끊는다. 없으면 아무것도 안 한다. */
    public void stop(long runId) {
        AgentStream stream = open.get(runId);
        if (stream != null) {
            stream.close();
        }
    }

    private void read(ChagokService.Launch launch, AtomicBoolean timedOut) {
        long runId = launch.runId();
        try (AgentStream stream = ai.agent(launch.request())) {
            open.put(runId, stream);
            try {
                if (!service.isRunning(runId)) {
                    return;
                }
                String line;
                while ((line = stream.nextLine()) != null) {
                    if (!line.isBlank() && service.apply(runId, line)) {
                        return;
                    }
                }
            } finally {
                open.remove(runId);
            }
        }
        if (timedOut.get()) {
            throw new AiCallException("AI_TIMEOUT", timeoutMessage());
        }
        // 그만둬서 끊겼으면 일이 RUNNING 이 아니라 service.fail 이 아무것도 안 한다
        throw new AiCallException("INVALID_AGENT_OUTPUT", "AI 응답이 계약을 벗어났습니다 — done · confirm · error 없이 끝났습니다");
    }

    private String timeoutMessage() {
        return "AI 서버가 " + timeoutSeconds + "초 안에 일을 끝내지 못했습니다";
    }

    private void fail(long runId, String code, String message) {
        try {
            service.fail(runId, code, message);
        } catch (RuntimeException e) {
            log.error("차곡이 일 {} 을(를) 실패로 남기지 못했습니다", runId, e);
        }
    }

    private void revoke(long tokenId) {
        try {
            connections.revoke(tokenId);
        } catch (RuntimeException e) {
            // 지우지 못해도 10분 뒤 만료된다
            log.error("차곡이 토큰을 지우지 못했습니다", e);
        }
    }
}

package com.woowangthon.backend.ai;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** AI /agent 의 NDJSON 응답 — 한 줄씩 읽는다. 읽는 중에 다른 스레드가 close() 하면 읽기가 끝난다(그만두기 · 제한 시간). */
public final class AgentStream implements Closeable {

    private final InputStream body;
    private final BufferedReader lines;

    AgentStream(InputStream body) {
        this.body = body;
        this.lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    }

    /** 다음 줄. 스트림이 끝나면 null. */
    public String nextLine() {
        try {
            return lines.readLine();
        } catch (IOException e) {
            throw new AiCallException("AI_UNAVAILABLE", "AI 스트림이 끊겼습니다");
        }
    }

    // BufferedReader.close 는 읽는 스레드가 쥔 잠금을 기다리므로 원본 스트림을 닫는다
    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException ignored) {
            // 끊는 중이다 — 더 할 일이 없다
        }
    }
}

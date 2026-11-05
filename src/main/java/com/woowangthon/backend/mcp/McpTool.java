package com.woowangthon.backend.mcp;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * MCP 도구 하나 = 이 인터페이스를 구현한 빈 하나(ETC ADR-008 — 도구는 BE MCP 에만 정의하고, 차곡이도 이 서버의 클라이언트로 쓴다).
 * 도구를 더하면 ETC docs/contracts/mcp-api.md 「도구 목록」에도 더한다.
 */
public interface McpTool {

    String name();

    /** tools/list 에 실리는 정의 — name · title · description · inputSchema · annotations. */
    Map<String, Object> definition();

    /**
     * 토큰 주인(userId)의 데이터만 읽어 결과(JSON 으로 바뀔 값)를 돌려준다. 입력이 틀리거나 찾는 게 없으면 {@link McpToolException}.
     * arguments 는 늘 JSON 객체다(없으면 빈 객체).
     */
    Object call(long userId, JsonNode arguments);

    /** 읽기 전용 도구의 annotations. */
    static Map<String, Object> readOnly() {
        return Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false);
    }

    /** 쓰기 도구의 annotations. */
    static Map<String, Object> write() {
        return Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false);
    }
}

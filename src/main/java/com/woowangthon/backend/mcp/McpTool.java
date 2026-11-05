package com.woowangthon.backend.mcp;

import com.woowangthon.backend.mcp.McpConnectionService.Authenticated;
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

    /** 그 범위의 tools/list 에 실리는 정의 — 범위마다 설명이 다른 도구만 바꾼다. */
    default Map<String, Object> definitionFor(String scope) {
        return definition();
    }

    /** 그 범위에 보이고 불리나(계약 「토큰 범위」). 안 보이면 없는 도구와 같다. 읽기 도구는 모두에게 보인다. */
    default boolean visibleTo(String scope) {
        return true;
    }

    /**
     * 토큰 주인(caller.userId)의 것만 다뤄 결과(JSON 으로 바뀔 값)를 돌려준다. 입력이 틀리거나 찾는 게 없으면 {@link McpToolException}.
     * 웹 세션이 없으므로 CurrentUser 를 쓰는 코드를 부르지 않는다. arguments 는 늘 JSON 객체다(없으면 빈 객체).
     */
    Object call(Authenticated caller, JsonNode arguments);

    /** 읽기 전용 도구의 annotations. */
    static Map<String, Object> readOnly() {
        return Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false);
    }

    /** 있던 값을 바꾸는 쓰기 도구의 annotations. */
    static Map<String, Object> destructiveWrite() {
        return Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", false);
    }

    /** 쓰기 도구의 annotations. */
    static Map<String, Object> write() {
        return Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false);
    }
}

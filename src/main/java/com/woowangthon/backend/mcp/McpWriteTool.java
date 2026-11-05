package com.woowangthon.backend.mcp;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 쓰기 도구 — CHAGOK 토큰의 tools/list 에만 보이고 MCP 로는 실행되지 않는다(계약 「쓰기 도구」).
 * 사용자가 확인하면 BE 가 {@link #execute}로 실행한다. 저장 · 변경은 확인 뒤에만(S-31).
 */
public interface McpWriteTool extends McpTool {

    /** 실행 결과. value 는 계약의 도구 결과, afterCommit 은 저장이 끝난 뒤 시작할 분석. */
    record Written(Map<String, Object> value, Runnable afterCommit) {
    }

    @Override
    default Object call(long userId, JsonNode arguments) {
        throw new McpToolException("CONFIRM_REQUIRED", "쓰기는 사용자 확인 뒤 BE 가 실행한다 — 직접 부르지 말고 confirm 으로 요청하라");
    }

    /**
     * 그 도구의 REST API 와 같은 코드로 실행한다 — 로그인한 사용자의 요청 안에서, 부르는 쪽 트랜잭션에 묶여서.
     * 입력 검사 · 한도 실패는 그 API 의 ApiException 그대로.
     */
    Written execute(JsonNode arguments);
}

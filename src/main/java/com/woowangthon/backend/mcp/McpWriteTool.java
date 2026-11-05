package com.woowangthon.backend.mcp;

import com.woowangthon.backend.mcp.McpConnectionService.Authenticated;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 차곡이가 쓰는 쓰기 도구 — CHAGOK 토큰에 보이지만 MCP 로는 실행되지 않는다(계약 「쓰기 도구」).
 * 사용자가 확인하면 BE 가 {@link #execute}로 실행한다. 저장 · 변경은 확인 뒤에만(S-31).
 * 외부 연결(READ_WRITE)에서 바로 실행되는 도구는 visibleTo · call 을 범위에 따라 바꾼다(AddSourceTool).
 */
public interface McpWriteTool extends McpTool {

    /** 실행 결과. value 는 계약의 도구 결과, afterCommit 은 저장이 끝난 뒤 시작할 분석. */
    record Written(Map<String, Object> value, Runnable afterCommit) {
    }

    @Override
    default boolean visibleTo(String scope) {
        return AccessToken.SCOPE_CHAGOK.equals(scope);
    }

    @Override
    default Object call(Authenticated caller, JsonNode arguments) {
        throw new McpToolException("CONFIRM_REQUIRED", "쓰기는 사용자 확인 뒤 BE 가 실행한다 — 직접 부르지 말고 confirm 으로 요청하라");
    }

    /**
     * 그 도구의 REST API 와 같은 코드로 실행한다 — 로그인한 사용자의 요청 안에서, 부르는 쪽 트랜잭션에 묶여서.
     * 입력 검사 · 한도 실패는 그 API 의 ApiException 그대로.
     */
    Written execute(JsonNode arguments);
}

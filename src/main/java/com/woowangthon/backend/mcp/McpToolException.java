package com.woowangthon.backend.mcp;

/** 도구 안의 실패 — JSON-RPC 정상 응답에 isError: true 로 돌려준다(계약 「도구 오류」). code 는 계약 표의 값만. */
public class McpToolException extends RuntimeException {

    private final String code;

    public McpToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static McpToolException invalidArgument(String message) {
        return new McpToolException("INVALID_ARGUMENT", message);
    }

    public String code() {
        return code;
    }
}

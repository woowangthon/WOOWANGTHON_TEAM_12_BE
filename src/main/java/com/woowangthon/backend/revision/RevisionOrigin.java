package com.woowangthon.backend.revision;

/** 이 기록을 누가 만들었나 — by · 외부 AI 연결 이름 · 이어진 외부 AI 변경(McpChange.id). */
public record RevisionOrigin(RevisionBy by, String client, Long mcpChangeId) {

    /** 웹에서 사용자가 직접. */
    public static final RevisionOrigin USER = new RevisionOrigin(RevisionBy.USER, null, null);

    /** 외부 AI(MCP 쓰기 도구)가 그 변경으로 고쳤다. */
    public static RevisionOrigin externalAi(String client, long mcpChangeId) {
        return new RevisionOrigin(RevisionBy.EXTERNAL_AI, client, mcpChangeId);
    }

    /** 사용자가 변경 기록에서 그 외부 AI 변경을 되돌리거나 맞아요 했다. */
    public static RevisionOrigin userOnChange(long mcpChangeId) {
        return new RevisionOrigin(RevisionBy.USER, null, mcpChangeId);
    }
}

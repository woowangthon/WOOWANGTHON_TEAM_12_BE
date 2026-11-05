package com.woowangthon.backend.ai;

/** ai-api.md 「POST /agent」 요청. mcpUrl 에 이 일의 토큰이 들어 있다 — 로그에 남기지 않는다. */
public record AgentRequest(String text, Context context, String mcpUrl, int maxToolCalls) {

    public record Context(QuestionPart question) {
    }

    public record QuestionPart(long id, String text, String organization) {
    }
}

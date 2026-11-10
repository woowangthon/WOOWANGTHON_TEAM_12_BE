package com.woowangthon.backend.ai;

import java.util.List;

/** ai-api.md 「POST /agent」 요청. mcpUrl 에 이 일의 토큰이 들어 있다 — 로그에 남기지 않는다. */
public record AgentRequest(String text, Context context, String mcpUrl, int maxToolCalls) {

    public record Context(QuestionPart question) {
    }

    /** experiences — 그 문항에 지금 걸린 경험: 추천 순서, 그 뒤 초안에만 쓴 경험(ai-api.md 「POST /agent」 · ETC#300). */
    public record QuestionPart(long id, String text, String organization, List<ExperiencePart> experiences) {
    }

    public record ExperiencePart(long id, String activityName, String content, boolean recommended, boolean inDraft) {
    }
}

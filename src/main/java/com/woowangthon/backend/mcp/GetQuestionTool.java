package com.woowangthon.backend.mcp;

import com.woowangthon.backend.mcp.McpConnectionService.Authenticated;
import com.woowangthon.backend.question.QuestionService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * get_question — GET /api/questions/{questionId} 에서 draft · answer · answerDraft(쓰는 중인 답 · S-72)를 뺀 응답
 * (계약 「외부 쓰기 도구」 · S-55) — 사용자가 웹에서 쓰는 글은 외부 AI 로 나가지 않는다(ETC#316).
 * 문항 · 추천은 지금 연결의 전송 범위보다 넓어서 읽기 도구지만 READ_WRITE 에만 보인다(ADR-011) — 차곡이 도구 목록도 그대로다.
 */
@Component
public class GetQuestionTool implements McpTool {

    private final QuestionService questions;
    private final JsonMapper json;

    public GetQuestionTool(QuestionService questions, JsonMapper json) {
        this.questions = questions;
        this.json = json;
    }

    @Override
    public String name() {
        return "get_question";
    }

    @Override
    public boolean visibleTo(String scope) {
        return AccessToken.SCOPE_READ_WRITE.equals(scope);
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of(
                "name", name(),
                "title", "문항 · 추천 읽기",
                "description", """
                        문항 하나의 요구사항 · 추천 · 되묻기를 돌려준다. questionId 는 create_application 결과의 questionIds. \
                        status 가 ANALYZING 이면 아직 분석 중이라 비어 있다 — 잠시 뒤 다시 부른다. \
                        FAILED 면 실패했다고 말하고 추천을 지어내지 않는다. 초안 · 쓰는 중인 답 · 확정 답변은 싣지 않는다 — 웹에서 한다.""",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("questionId", Map.of("type", "integer", "description", "문항 id")),
                        "required", List.of("questionId"),
                        "additionalProperties", false),
                "annotations", McpTool.readOnly());
    }

    @Override
    public Object call(Authenticated caller, JsonNode arguments) {
        long questionId = McpArgs.integer(arguments, "questionId")
                .orElseThrow(() -> McpToolException.invalidArgument("questionId 가 없습니다"));
        ObjectNode question = json.valueToTree(questions.get(caller.userId(), questionId));
        question.remove("draft");
        question.remove("answer");
        question.remove("answerDraft");
        return question;
    }
}

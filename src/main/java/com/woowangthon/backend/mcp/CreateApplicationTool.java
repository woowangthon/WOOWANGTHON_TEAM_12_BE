package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.question.ApplicationService;
import com.woowangthon.backend.question.MatchRunner;
import com.woowangthon.backend.question.QuestionResponses;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** create_application — POST /api/applications 와 같은 검사 · 실행(계약 「쓰기 도구」). */
@Component
public class CreateApplicationTool implements McpWriteTool {

    private final ApplicationService applications;
    private final MatchRunner matches;
    private final JsonMapper json;

    public CreateApplicationTool(ApplicationService applications, MatchRunner matches, JsonMapper json) {
        this.applications = applications;
        this.matches = matches;
        this.json = json;
    }

    @Override
    public String name() {
        return "create_application";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of(
                "name", name(),
                "title", "지원 만들기",
                "description", """
                        지원처 하나와 문항 1 ~ 10개로 지원을 만든다. 사용자 확인 뒤 BE 가 실행한다 — 직접 부르지 말고 confirm 으로 요청하라. \
                        문항은 사용자가 준 글 · 맥락 문항에 적힌 것만 싣는다. 문항을 지어내지 않는다.""",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "organization", Map.of("type", "string", "description", "지원처 이름. 100자 이하"),
                                "organizationType", Map.of("type", List.of("string", "null"), "description", "지원처 종류. 모르면 null"),
                                "jobRole", Map.of("type", List.of("string", "null"), "description", "직무. 모르면 null"),
                                "postingUrl", Map.of("type", List.of("string", "null"), "description", "공고 원문 링크 — 사용자가 준 http(s) 링크만. 없으면 null"),
                                "questions", Map.of(
                                        "type", "array",
                                        "minItems", 1,
                                        "maxItems", 10,
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of("text", Map.of("type", "string")),
                                                "required", List.of("text")))),
                        "required", List.of("organization", "questions")),
                "annotations", McpTool.write());
    }

    @Override
    public Written execute(JsonNode arguments) {
        ApplicationService.ApplicationRequest request;
        try {
            request = json.treeToValue(arguments, ApplicationService.ApplicationRequest.class);
        } catch (JacksonException e) {
            throw ApiException.invalidRequest("create_application 입력 모양이 틀렸습니다");
        }
        QuestionResponses.ApplicationSubmitted submitted = applications.submit(request);
        return new Written(
                Map.of("applicationId", submitted.applicationId(), "questionIds", submitted.questionIds()),
                () -> submitted.questionIds().forEach(matches::run));
    }
}

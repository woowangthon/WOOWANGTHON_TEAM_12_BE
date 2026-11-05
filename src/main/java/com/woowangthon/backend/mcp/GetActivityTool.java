package com.woowangthon.backend.mcp;

import com.woowangthon.backend.activity.ActivityService;
import com.woowangthon.backend.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** get_activity — GET /api/activities/{activityId} 와 같은 응답. 남의 활동은 없는 것과 같이 ACTIVITY_NOT_FOUND. */
@Component
public class GetActivityTool implements McpTool {

    private final ActivityService activities;

    public GetActivityTool(ActivityService activities) {
        this.activities = activities;
    }

    @Override
    public String name() {
        return "get_activity";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of(
                "name", name(),
                "title", "활동 하나 보기",
                "description", """
                        활동 하나의 경험 전부를 근거(원본 이름 · 발췌문) · 출처 상태 · 보완(사용자가 직접 답한 것)과 함께 돌려준다. \
                        activityId 는 list_activities 나 search_experiences 결과의 활동 id.""",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("activityId", Map.of("type", "integer", "description", "활동 id")),
                        "required", List.of("activityId"),
                        "additionalProperties", false),
                "annotations", McpTool.readOnly());
    }

    @Override
    public Object call(McpConnectionService.Authenticated caller, JsonNode arguments) {
        long activityId = McpArgs.integer(arguments, "activityId")
                .orElseThrow(() -> McpToolException.invalidArgument("activityId 가 없습니다"));
        try {
            return activities.mcpDetail(caller.userId(), activityId);
        } catch (ApiException e) {
            if ("ACTIVITY_NOT_FOUND".equals(e.code())) {
                throw new McpToolException(e.code(), e.getMessage());
            }
            throw e;
        }
    }
}

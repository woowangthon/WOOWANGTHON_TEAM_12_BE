package com.woowangthon.backend.mcp;

import com.woowangthon.backend.activity.ActivityService;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** list_activities — GET /api/activities 와 같은 응답(계약 「도구 목록」). */
@Component
public class ListActivitiesTool implements McpTool {

    private final ActivityService activities;

    public ListActivitiesTool(ActivityService activities) {
        this.activities = activities;
    }

    @Override
    public String name() {
        return "list_activities";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of(
                "name", name(),
                "title", "활동 목록",
                "description", """
                        사용자의 활동(프로젝트 · 동아리 · 수업 · 대회 같은 묶음) 목록을 시작 시기 순서로 돌려준다. \
                        활동마다 id · 이름 · 기간 · 경험 수. 경험 · 근거를 보려면 get_activity 에 id 를 넘긴다.""",
                "inputSchema", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                "annotations", McpTool.readOnly());
    }

    @Override
    public Object call(McpConnectionService.Authenticated caller, JsonNode arguments) {
        return activities.mcpTimeline(caller.userId());
    }
}

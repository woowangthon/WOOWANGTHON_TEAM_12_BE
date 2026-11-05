package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.job.AnalysisService;
import com.woowangthon.backend.job.ExtractionRunner;
import com.woowangthon.backend.job.JobResponses;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** add_source — POST /api/sources 에 kind TEXT 로 넣은 것과 같다(계약 「쓰기 도구」). */
@Component
public class AddSourceTool implements McpWriteTool {

    private final AnalysisService analyses;
    private final ExtractionRunner extractions;

    public AddSourceTool(AnalysisService analyses, ExtractionRunner extractions) {
        this.analyses = analyses;
        this.extractions = extractions;
    }

    @Override
    public String name() {
        return "add_source";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of(
                "name", name(),
                "title", "자료 넣기",
                "description", """
                        사용자가 부탁에 붙여 넣은 글을 자료로 넣고 분석을 시작한다. 사용자 확인 뒤 BE 가 실행한다 — 직접 부르지 말고 confirm 으로 요청하라. \
                        text 는 사용자가 넣은 글 그대로다 — 요약하거나 고쳐 쓰지 않는다.""",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string", "description", "사용자가 넣은 글 그대로. 50000자 이하")),
                        "required", List.of("text")),
                "annotations", McpTool.write());
    }

    @Override
    public Written execute(JsonNode arguments) {
        JsonNode text = arguments.path("text");
        if (!text.isMissingNode() && !text.isNull() && !text.isString()) {
            throw ApiException.invalidRequest("text 는 문자열이어야 합니다");
        }
        JobResponses.Submitted submitted = analyses.submit(
                new AnalysisService.SourceRequest("TEXT", null, text.isString() ? text.asString() : null, null));
        return new Written(Map.of("sourceId", submitted.sourceId(), "jobId", submitted.jobId()),
                () -> extractions.run(submitted.jobId()));
    }
}

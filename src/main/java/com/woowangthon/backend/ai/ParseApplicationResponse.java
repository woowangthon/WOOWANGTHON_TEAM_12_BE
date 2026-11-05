package com.woowangthon.backend.ai;

import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import java.util.List;

/** ai-api.md 「POST /parse-application」 응답. 종류 · 직무가 값 목록 밖이면 JSON 을 읽지 못해 AI_FAILED 다. */
public record ParseApplicationResponse(String organization, OrganizationType organizationType, JobRole jobRole,
        List<String> questions, String model) {
}

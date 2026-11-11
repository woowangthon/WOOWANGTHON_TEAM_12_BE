package com.woowangthon.backend.ai;

import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import com.woowangthon.backend.question.Ksa;
import com.woowangthon.backend.question.PostingSection;
import com.woowangthon.backend.question.ReadFrom;
import java.util.List;

/** ai-api.md 「POST /parse-application」 응답. 종류 · 직무가 값 목록 밖이면 JSON 을 읽지 못해 AI_FAILED 다. */
public record ParseApplicationResponse(String organization, OrganizationType organizationType, JobRole jobRole,
        List<String> questions, List<PostingLine> postingLines, ReadFrom readFrom, String model) {

    public record PostingLine(PostingSection section, String text, Ksa ksa) {
        @Override
        public String toString() { return "PostingLine[redacted]"; }
    }

    @Override
    public String toString() { return "ParseApplicationResponse[redacted]"; }
}

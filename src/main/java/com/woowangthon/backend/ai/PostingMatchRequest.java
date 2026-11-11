package com.woowangthon.backend.ai;

import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.Ksa;
import com.woowangthon.backend.question.PostingSection;
import java.util.List;

/** ai-api.md 「POST /posting-match」 요청 — 지원의 공고 요건 줄 · 직무와 그 사용자의 경험 전부(「POST /match」와 같은 모양). */
public record PostingMatchRequest(JobRole jobRole, List<LinePart> lines, List<MatchRequest.ExperiencePart> experiences) {

    public record LinePart(Long id, PostingSection section, String text, Ksa ksa) {
    }
}

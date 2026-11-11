package com.woowangthon.backend.ai;

import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Competency;
import java.util.List;

/** ai-api.md 「POST /star」 요청 — 한 활동의 경험 1 ~ 30개. 근거는 원본에서 찾은 것만, 보완은 사용자가 정한 것만. */
public record StarRequest(List<ExperiencePart> experiences) {

    public static final int MAX_EXPERIENCES = 30;

    public record ExperiencePart(Long id, String content, Actor actor, List<Competency> competencies,
            List<CitationPart> citations, List<SupplementPart> supplements) {
    }

    public record CitationPart(Long id, String quote) {
    }

    public record SupplementPart(Long id, String question, String answer) {
    }
}

package com.woowangthon.backend.revision;

import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.SourceStatus;
import java.util.List;

/**
 * 경험 스냅샷(계약 「스냅샷」) — 본문 · 수행 주체 · 역량 · 역량 출처 · 출처 상태. 근거 · 보완 · 활동 소속 · 저장 표시 · 공개는 남기지 않는다.
 * 같은지 비교에도 쓴다(역량은 순서까지 같아야 같다).
 */
public record ExperienceSnapshot(String content, Actor actor, List<Competency> competencies,
        CompetenciesBy competenciesBy, SourceStatus sourceStatus) {

    public static ExperienceSnapshot of(Experience e) {
        return new ExperienceSnapshot(e.getContent(), e.getActor(), e.getCompetencies(), e.getCompetenciesBy(),
                e.getSourceStatus());
    }
}

package com.woowangthon.backend.question;

import com.woowangthon.backend.experience.Competency;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 문항 유형 → 겹치는 역량 — 초안의 보조 재료를 고르는 순서에만 쓰는 코드 표다(ETC docs/contracts/README.md 「문항 유형 → 겹치는 역량」, S-75 · S-84).
 * 매칭 후보를 거르는 데 쓰지 않는다 — 역량은 AI 해석이라 거르면 맞는 경험이 소리 없이 빠진다. 표에 없는 유형(MOTIVATION · SELF_INTRO ·
 * STRENGTH_WEAKNESS · OTHER)과 유형 없음(null)은 겹치는 역량이 없다 — 최근 순만.
 */
final class QuestionTypeCompetencies {

    private static final Map<QuestionType, List<Competency>> TABLE = new EnumMap<>(QuestionType.class);

    static {
        TABLE.put(QuestionType.PROBLEM_SOLVING,
                List.of(Competency.PROBLEM_SOLVING, Competency.IMPLEMENTATION, Competency.ANALYSIS));
        TABLE.put(QuestionType.COLLABORATION,
                List.of(Competency.COLLABORATION, Competency.COMMUNICATION, Competency.LEADERSHIP));
        TABLE.put(QuestionType.CONFLICT, List.of(Competency.COLLABORATION, Competency.COMMUNICATION));
        TABLE.put(QuestionType.ACHIEVEMENT,
                List.of(Competency.INITIATIVE, Competency.PERSEVERANCE, Competency.RESPONSIBILITY));
        TABLE.put(QuestionType.FAILURE, List.of(Competency.PERSEVERANCE, Competency.LEARNING));
        TABLE.put(QuestionType.GROWTH, List.of(Competency.LEARNING));
        TABLE.put(QuestionType.IDEA, List.of(Competency.PLANNING, Competency.INITIATIVE));
        TABLE.put(QuestionType.JOB_SKILL,
                List.of(Competency.IMPLEMENTATION, Competency.LEARNING, Competency.ANALYSIS));
    }

    private QuestionTypeCompetencies() {
    }

    static List<Competency> of(QuestionType type) {
        return type == null ? List.of() : TABLE.getOrDefault(type, List.of());
    }

    /** 경험의 역량에 그 유형의 표 값이 하나라도 있나. */
    static boolean overlaps(QuestionType type, Collection<Competency> competencies) {
        List<Competency> wanted = of(type);
        return competencies.stream().anyMatch(wanted::contains);
    }
}

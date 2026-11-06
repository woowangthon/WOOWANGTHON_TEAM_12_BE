package com.woowangthon.backend.experience;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityCategory;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경험 찾기 — 앱의 `GET /api/experiences`(S-58)와 MCP `search_experiences` 가 같이 쓰는 **한 검색**이다.
 * 같은 입력(낱말 · 역량 · 연도)이면 같은 경험 · 같은 순서가 나온다 — 나중에 의미 검색으로 바꿔도 둘이 같이 바뀌게 한 곳에 둔다(BE#96).
 *
 * <ul>
 *   <li>낱말 맞추기다(의미 검색 아님, AI 를 부르지 않는다). 조건은 모두 AND, 낱말은 하나라도(OR) —
 *       경험 본문 · 근거 발췌문 · 보완 질문과 답 · 활동 이름에서 대소문자 무시 · 부분 일치로 찾는다.</li>
 *   <li>순서: 맞은 낱말 수 많은 것 → 활동 시작 최근 것(null 은 뒤) → 경험 id.</li>
 *   <li>출처 상태 · 활동 분류 · 저장은 앱에서만 쓴다 — MCP 입력엔 없다(S-58 · S-71 범위 제외, 분류는 MCP 에 싣지 않는다 — S-57).</li>
 *   <li>입력 확인 · 오류 모양 · 응답 모양은 부르는 쪽이 각자 계약대로 한다 — MCP 는 도구 오류, 앱은 400.</li>
 * </ul>
 *
 * 규칙(낱말 · 역량 · 연도 · 순서 · limit · total)의 시험은 McpApiTest 「search_experiences」가 맡는다. 앱 쪽 시험
 * (ExperienceSearchApiTest)은 앱에만 있는 것(쿼리 읽기 · 출처 상태 · 분류 · 저장 · 400 · 응답 모양)과 「MCP 와 같은 결과」만 본다 —
 * 같은 규칙을 두 곳에서 시험하면 규칙이 바뀔 때 한쪽만 고치기 쉽다.
 *
 * 사용자의 경험을 전부 읽어 메모리에서 거른다 — 한 사람의 경험은 많아야 수백 개라 충분하다(느리면 그때 쿼리로).
 */
@Service
public class ExperienceSearch {

    static final int APP_DEFAULT_LIMIT = 100;
    static final int APP_MAX_LIMIT = 200;
    private static final String YEAR = "\\d{4}";

    private final ExperienceRepository experiences;

    public ExperienceSearch(ExperienceRepository experiences) {
        this.experiences = experiences;
    }

    /**
     * 거를 조건 — null 인 칸은 거르지 않는다. terms 가 빈 목록이면 낱말로 거르지 않는다.
     * competencies 는 빈 집합이어도 「그중 하나라도」라 아무것도 안 맞는다 — MCP 가 `[]` 를 받았을 때의 예전 동작 그대로.
     * 앱은 빈 칸을 null 로 넘긴다. savedOnly 가 true 면 저장한 경험만, false 면 거르지 않는다.
     */
    public record Filter(List<String> terms, Set<Competency> competencies, Integer year, SourceStatus sourceStatus,
            ActivityCategory category, boolean savedOnly) {

        /** MCP — 낱말 · 역량 · 연도만. */
        public static Filter of(List<String> terms, Set<Competency> competencies, Integer year) {
            return new Filter(terms, competencies, year, null, null, false);
        }
    }

    /** total — limit 을 적용하기 전 맞은 개수. experiences — 순서대로 limit 개까지. */
    public record Result(int total, List<Experience> experiences) {
    }

    // ── 앱 응답(계약 experience-api.md 「GET /api/experiences」) ─────────────────────────────
    // 경험 하나는 활동 세부 · 추천 등과 같은 ExperienceResponse 를 그대로 감싸고 activityId 대신 activity 를 싣는다.
    // 그래서 ExperienceResponse 에 칸이 더해지면(예: 외부 쓰기 BE#92 의 mcpChange) 검색 응답에도 따라 실린다.

    public record AppResult(int total, List<AppHit> experiences) {
    }

    public record AppHit(@JsonUnwrapped @JsonIgnoreProperties("activityId") ExperienceResponse experience,
            AppActivity activity) {
    }

    /** 경험이 속한 활동 — 누르면 FE 가 id 로 활동 세부를 연다. 분류(S-57)는 앱에서 거르고 보이려고 싣는다. */
    public record AppActivity(Long id, String name, Period period, ActivityCategory category) {
    }

    @Transactional(readOnly = true)
    public Result search(long userId, Filter filter, long limit) {
        record Scored(Experience experience, int matchedTerms) {
        }
        List<String> terms = filter.terms() == null ? List.of() : filter.terms();
        List<Scored> matched = new ArrayList<>();
        for (Experience e : experiences.findAllOfUser(userId)) {
            if (filter.competencies() != null && e.getCompetencies().stream().noneMatch(filter.competencies()::contains)) {
                continue;
            }
            if (filter.year() != null && !overlaps(e.getActivity().getPeriod(), filter.year())) {
                continue;
            }
            if (filter.sourceStatus() != null && e.getSourceStatus() != filter.sourceStatus()) {
                continue;
            }
            // 분류를 주면 미분류 활동의 경험은 빠진다(S-57 — 미분류는 「전체」에만)
            if (filter.category() != null && e.getActivity().getCategory() != filter.category()) {
                continue;
            }
            if (filter.savedOnly() && !e.isSaved()) {
                continue;
            }
            int count = terms.isEmpty() ? 0 : countMatchedTerms(e, terms);
            if (!terms.isEmpty() && count == 0) {
                continue;
            }
            matched.add(new Scored(e, count));
        }

        Comparator<String> recentFirst = Comparator.nullsLast(Comparator.<String>reverseOrder());
        List<Experience> ordered = matched.stream()
                .sorted(Comparator.comparingInt(Scored::matchedTerms).reversed()
                        .thenComparing(s -> s.experience().getActivity().getPeriod().start(), recentFirst)
                        .thenComparing(s -> s.experience().getId()))
                .limit(limit)
                .map(Scored::experience)
                .toList();
        return new Result(matched.size(), ordered);
    }

    /** 앱 `GET /api/experiences` — 쿼리를 확인하고(틀리면 400 INVALID_REQUEST) 찾아서 계약 모양으로. 다 선택이다. */
    @Transactional(readOnly = true)
    public AppResult searchForApp(long userId, String query, String competencies, String year, String sourceStatus,
            String category, String saved, Integer limit) {
        int max = limit == null ? APP_DEFAULT_LIMIT : limit;
        if (max < 1 || max > APP_MAX_LIMIT) {
            throw ApiException.invalidRequest("limit 은 1 ~ " + APP_MAX_LIMIT + " 이어야 합니다");
        }
        Filter filter = new Filter(terms(query), appCompetencies(competencies), appYear(year),
                appEnum(SourceStatus.class, "sourceStatus", sourceStatus),
                appEnum(ActivityCategory.class, "category", category), appSavedOnly(saved));
        Result result = search(userId, filter, max);
        return new AppResult(result.total(), result.experiences().stream().map(ExperienceSearch::appHit).toList());
    }

    /** 공백으로 나눈 낱말 — 소문자 · 중복 없이. null · 빈 문자열이면 빈 목록(낱말로 거르지 않는다). */
    public static List<String> terms(String query) {
        if (query == null) {
            return List.of();
        }
        return Stream.of(query.toLowerCase(Locale.ROOT).trim().split("\\s+")).filter(t -> !t.isEmpty()).distinct()
                .toList();
    }

    /** YYYY 인가 — MCP · 앱이 같은 규칙으로 확인한다. */
    public static boolean isYear(String value) {
        return value.matches(YEAR);
    }

    // 시작을 모르면 빠진다. 진행 중이면 올해까지, 끝을 모르면(진행 중도 아니면) 시작한 해 하나로 본다
    private static boolean overlaps(Period period, int year) {
        if (period.start() == null) {
            return false;
        }
        int start = Integer.parseInt(period.start().substring(0, 4));
        int end = period.ongoing() ? Year.now(Times.SEOUL).getValue()
                : period.end() != null ? Integer.parseInt(period.end().substring(0, 4)) : start;
        return start <= year && year <= end;
    }

    private static int countMatchedTerms(Experience e, List<String> terms) {
        List<String> fields = new ArrayList<>();
        fields.add(e.getContent());
        fields.add(e.getActivity().getName());
        e.getCitations().forEach(c -> fields.add(c.getQuote()));
        e.getSupplements().forEach(s -> {
            fields.add(s.getQuestion());
            fields.add(s.getAnswer());
        });
        String haystack = String.join("\n", fields).toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(haystack::contains).count();
    }

    private static AppHit appHit(Experience e) {
        Activity a = e.getActivity();
        return new AppHit(ExperienceResponse.from(e), new AppActivity(a.getId(), a.getName(), a.getPeriod(), a.getCategory()));
    }

    // 쉼표로 나눈 역량 — 빈 칸은 건너뛰고, 목록 밖 값은 400. 다 비면 거르지 않는다
    private static Set<Competency> appCompetencies(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Set<Competency> result = EnumSet.noneOf(Competency.class);
        for (String part : value.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            result.add(Competency.parse(name)
                    .orElseThrow(() -> ApiException.invalidRequest("competencies 에 목록 밖 값이 있습니다: " + name)));
        }
        return result.isEmpty() ? null : result;
    }

    // 비우면 거르지 않는다(FE 가 지운 칸을 빈 값으로 보내도 되게). YYYY 가 아니면 400
    private static Integer appYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!isYear(value)) {
            throw ApiException.invalidRequest("year 는 YYYY 여야 합니다");
        }
        return Integer.parseInt(value);
    }

    // true 만 받는다 — 비우면 거르지 않고, false 를 포함한 그 밖의 값은 400
    private static boolean appSavedOnly(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!value.equals("true")) {
            throw ApiException.invalidRequest("saved 는 true 만 받습니다");
        }
        return true;
    }

    private static <E extends Enum<E>> E appEnum(Class<E> type, String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest(name + " 이(가) 목록 밖 값입니다: " + value);
        }
    }
}

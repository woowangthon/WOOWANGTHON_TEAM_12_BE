package com.woowangthon.backend.mcp;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.ExperienceResponse.CitationResponse;
import com.woowangthon.backend.experience.ExperienceResponse.SupplementResponse;
import com.woowangthon.backend.experience.SourceStatus;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * search_experiences — 낱말 맞추기 검색(의미 검색 아님, AI 를 쓰지 않는다). 계약 「search_experiences」:
 * 조건은 모두 AND, 낱말은 하나라도(OR). 순서는 맞은 낱말 수 많은 것 → 활동 시작 최근 것(null 뒤) → 경험 id.
 * 사용자의 경험을 전부 읽어 메모리에서 거른다 — 한 사람의 경험은 많아야 수백 개라 충분하다.
 */
@Component
public class SearchExperiencesTool implements McpTool {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final ExperienceRepository experiences;

    public SearchExperiencesTool(ExperienceRepository experiences) {
        this.experiences = experiences;
    }

    /** 계약의 Experience 에서 activityId 대신 activity(ActivitySummary 에서 experienceCount 를 뺀 것). */
    public record Hit(
            Long id,
            ActivityRef activity,
            String content,
            List<Competency> competencies,
            CompetenciesBy competenciesBy,
            Actor actor,
            SourceStatus sourceStatus,
            List<CitationResponse> citations,
            List<SupplementResponse> supplements) {
    }

    public record ActivityRef(Long id, String name, Period period) {
    }

    public record Result(int total, List<Hit> experiences) {
    }

    @Override
    public String name() {
        return "search_experiences";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of(
                "name", name(),
                "title", "경험 찾기",
                "description", """
                        사용자가 쌓은 경험을 낱말 · 역량 · 연도로 찾아 근거(원본 이름 · 발췌문) · 출처 상태 · 보완과 함께 돌려준다. \
                        낱말 맞추기다(의미 검색이 아니다) — query 를 공백으로 나눈 낱말 중 하나라도 경험 본문 · 근거 발췌문 · \
                        보완 질문과 답 · 활동 이름에 들어 있으면 맞는다(대소문자 무시, 부분 일치). 맞는 게 적으면 낱말을 바꿔(동의어 · \
                        영어/한국어) 여러 번 부르거나, 조건 없이 불러 전부 읽고 골라라. 결과가 없다는 것은 「그런 경험이 없다」가 아니라 \
                        「쌓인 자료에서 찾지 못했다」이다.""",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "공백으로 나눈 낱말 — 하나라도 들어 있는 경험. 비우면 낱말로 거르지 않는다"),
                                "competencies", Map.of("type", "array",
                                        "items", Map.of("type", "string",
                                                "enum", Arrays.stream(Competency.values()).map(Enum::name).toList()),
                                        "description", "이 역량 중 하나라도 붙은 경험"),
                                "year", Map.of("type", "string", "pattern", "^\\d{4}$",
                                        "description", "YYYY — 활동 기간이 그해와 겹치는 경험(시작을 모르는 활동은 빠진다)"),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_LIMIT,
                                        "default", DEFAULT_LIMIT, "description", "최대 몇 개")),
                        "additionalProperties", false),
                "annotations", McpTool.readOnly());
    }

    @Override
    @Transactional(readOnly = true)
    public Result call(long userId, JsonNode arguments) {
        List<String> terms = McpArgs.string(arguments, "query")
                .map(q -> Stream.of(q.toLowerCase(Locale.ROOT).trim().split("\\s+")).filter(t -> !t.isEmpty()).distinct().toList())
                .orElse(List.of());
        Set<Competency> competencies = McpArgs.strings(arguments, "competencies").map(SearchExperiencesTool::parseCompetencies)
                .orElse(null);
        Integer year = McpArgs.string(arguments, "year").map(SearchExperiencesTool::parseYear).orElse(null);
        long limit = McpArgs.integer(arguments, "limit").orElse((long) DEFAULT_LIMIT);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw McpToolException.invalidArgument("limit 은 1 ~ " + MAX_LIMIT + " 이어야 합니다");
        }

        record Scored(Experience experience, int matchedTerms) {
        }
        List<Scored> matched = new ArrayList<>();
        for (Experience e : experiences.findAllOfUser(userId)) {
            if (competencies != null && e.getCompetencies().stream().noneMatch(competencies::contains)) {
                continue;
            }
            if (year != null && !overlaps(e.getActivity().getPeriod(), year)) {
                continue;
            }
            int count = terms.isEmpty() ? 0 : countMatchedTerms(e, terms);
            if (!terms.isEmpty() && count == 0) {
                continue;
            }
            matched.add(new Scored(e, count));
        }

        Comparator<String> recentFirst = Comparator.nullsLast(Comparator.<String>reverseOrder());
        List<Hit> hits = matched.stream()
                .sorted(Comparator.comparingInt(Scored::matchedTerms).reversed()
                        .thenComparing(s -> s.experience().getActivity().getPeriod().start(), recentFirst)
                        .thenComparing(s -> s.experience().getId()))
                .limit(limit)
                .map(s -> toHit(s.experience()))
                .toList();
        return new Result(matched.size(), hits);
    }

    private static Set<Competency> parseCompetencies(List<String> values) {
        Set<Competency> result = EnumSet.noneOf(Competency.class);
        for (String value : values) {
            result.add(Competency.parse(value)
                    .orElseThrow(() -> McpToolException.invalidArgument("competencies 에 목록 밖 값이 있습니다: " + value)));
        }
        return result;
    }

    private static int parseYear(String value) {
        if (!value.matches("\\d{4}")) {
            throw McpToolException.invalidArgument("year 는 YYYY 여야 합니다");
        }
        return Integer.parseInt(value);
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

    private static Hit toHit(Experience e) {
        ExperienceResponse r = ExperienceResponse.from(e);
        Activity a = e.getActivity();
        return new Hit(r.id(), new ActivityRef(a.getId(), a.getName(), a.getPeriod()), r.content(), r.competencies(),
                r.competenciesBy(), r.actor(), r.sourceStatus(), r.citations(), r.supplements());
    }
}

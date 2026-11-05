package com.woowangthon.backend.activity;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.question.ApplicationStatus;
import com.woowangthon.backend.question.RecommendationRepository.UsedInRow;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 계약의 ActivitySummary · 활동 목록 · 활동 세부 모양. */
public final class ActivityResponses {

    private ActivityResponses() {
    }

    /** 같은 활동 제안(S-44) — 같아 보이는 기존 활동의 지금 이름 · 기간. */
    public record MergeSuggestion(Long activityId, String name, Period period) {

        public static MergeSuggestion of(Activity target) {
            return target == null ? null : new MergeSuggestion(target.getId(), target.getName(), target.getPeriod());
        }
    }

    public record Summary(Long id, String name, Period period, int experienceCount, String note,
            MergeSuggestion mergeSuggestion) {

        public static Summary from(Activity a, Activity suggested) {
            return new Summary(a.getId(), a.getName(), a.getPeriod(), a.getExperienceCount(), a.getNote(),
                    MergeSuggestion.of(suggested));
        }
    }

    public record Timeline(List<Summary> activities) {
    }

    /** 쓰인 지원 하나와 그 지원에서 이 경험이 추천된 문항들(S-38, 계약 「쓰인 지원 usedIn」). */
    public record UsedIn(Long applicationId, String organization, ApplicationStatus applicationStatus,
            String submittedMonth, List<UsedInQuestion> questions) {

        /** 받은 줄의 순서를 지킨 채 경험 → 지원으로 묶는다. */
        public static Map<Long, List<UsedIn>> byExperience(List<UsedInRow> rows) {
            return rows.stream().collect(Collectors.groupingBy(UsedInRow::experienceId,
                    Collectors.collectingAndThen(
                            Collectors.groupingBy(UsedInRow::applicationId, LinkedHashMap::new, Collectors.toList()),
                            byApplication -> byApplication.values().stream().map(UsedIn::from).toList())));
        }

        private static UsedIn from(List<UsedInRow> sameApplication) {
            UsedInRow first = sameApplication.getFirst();
            return new UsedIn(first.applicationId(), first.organization(), first.applicationStatus(),
                    first.submittedMonth(),
                    sameApplication.stream().map(r -> new UsedInQuestion(r.questionId(), r.questionText())).toList());
        }
    }

    public record UsedInQuestion(Long id, String text) {
    }

    /** 활동 세부의 경험 — Experience 모양에 usedIn 만 더한다. 그 밖의 응답엔 싣지 않으려고 ExperienceResponse 와 나눈다. */
    public record DetailExperience(@JsonUnwrapped ExperienceResponse experience, List<UsedIn> usedIn) {
    }

    /** 이어짐(S-35, 계약 「이어짐 ActivityLink」) — 활동 이름은 지금 값. */
    public record Link(Long id, Long fromActivityId, String fromActivityName, Long toActivityId, String toActivityName,
            String reason, LinkStatus status, OffsetDateTime createdAt) {

        public static Link from(ActivityLink l) {
            Activity from = l.getFromActivity();
            Activity to = l.getToActivity();
            return new Link(l.getId(), from.getId(), from.getName(), to.getId(), to.getName(), l.getReason(),
                    l.getStatus(), Times.seoul(l.getCreatedAt()));
        }
    }

    public record Links(List<Link> links) {
    }

    public record Detail(Long id, String name, Period period, String note, MergeSuggestion mergeSuggestion,
            List<DetailExperience> experiences, List<Link> links) {

        public static Detail from(Activity a, Activity suggested, Map<Long, List<UsedIn>> usedIn, List<Link> links) {
            return new Detail(a.getId(), a.getName(), a.getPeriod(), a.getNote(), MergeSuggestion.of(suggested),
                    a.getExperiences().stream().map(e -> detailExperience(e, usedIn)).toList(), links);
        }

        private static DetailExperience detailExperience(Experience e, Map<Long, List<UsedIn>> usedIn) {
            return new DetailExperience(ExperienceResponse.from(e), usedIn.getOrDefault(e.getId(), List.of()));
        }
    }

    // MCP 응답 — mergeSuggestion(화면에서 사용자가 정할 AI 제안) · note(S-34)를 싣지 않는다(mcp-api.md 「list_activities」)

    public record McpSummary(Long id, String name, Period period, int experienceCount) {

        public static McpSummary from(Activity a) {
            return new McpSummary(a.getId(), a.getName(), a.getPeriod(), a.getExperienceCount());
        }
    }

    public record McpTimeline(List<McpSummary> activities) {
    }

    public record McpDetail(Long id, String name, Period period, List<ExperienceResponse> experiences) {

        public static McpDetail from(Activity a) {
            return new McpDetail(a.getId(), a.getName(), a.getPeriod(),
                    a.getExperiences().stream().map(ExperienceResponse::from).toList());
        }
    }
}

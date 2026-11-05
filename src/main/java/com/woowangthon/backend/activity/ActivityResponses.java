package com.woowangthon.backend.activity;

import com.woowangthon.backend.experience.ExperienceResponse;
import java.util.List;

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

    public record Summary(Long id, String name, Period period, int experienceCount, MergeSuggestion mergeSuggestion) {

        public static Summary from(Activity a, Activity suggested) {
            return new Summary(a.getId(), a.getName(), a.getPeriod(), a.getExperienceCount(),
                    MergeSuggestion.of(suggested));
        }
    }

    public record Timeline(List<Summary> activities) {
    }

    public record Detail(Long id, String name, Period period, MergeSuggestion mergeSuggestion,
            List<ExperienceResponse> experiences) {

        public static Detail from(Activity a, Activity suggested) {
            return new Detail(a.getId(), a.getName(), a.getPeriod(), MergeSuggestion.of(suggested),
                    a.getExperiences().stream().map(ExperienceResponse::from).toList());
        }
    }

    // MCP 응답 — mergeSuggestion(화면에서 사용자가 정할 AI 제안)을 싣지 않는다(mcp-api.md 「list_activities」)

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

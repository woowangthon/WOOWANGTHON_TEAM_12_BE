package com.woowangthon.backend.activity;

import com.woowangthon.backend.experience.ExperienceResponse;
import java.util.List;

/** 계약의 ActivitySummary · 활동 목록 · 활동 세부 모양. */
public final class ActivityResponses {

    private ActivityResponses() {
    }

    public record Summary(Long id, String name, Period period, int experienceCount) {

        public static Summary from(Activity a) {
            return new Summary(a.getId(), a.getName(), a.getPeriod(), a.getExperienceCount());
        }
    }

    public record Timeline(List<Summary> activities) {
    }

    public record Detail(Long id, String name, Period period, List<ExperienceResponse> experiences) {

        public static Detail from(Activity a) {
            return new Detail(a.getId(), a.getName(), a.getPeriod(),
                    a.getExperiences().stream().map(ExperienceResponse::from).toList());
        }
    }
}

package com.woowangthon.backend.share;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityCategory;
import com.woowangthon.backend.activity.CategoryBy;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.SourceStatus;
import java.util.List;

/** 계약의 PublicProfileSettings · 공개 응답 모양(share-api.md). */
public final class PublicProfileResponses {

    private PublicProfileResponses() {
    }

    public record Settings(String title, boolean linkEnabled, String token, long sharedCount, long visibleCount) {
    }

    // 공개 응답은 허용 목록의 필드만 — ActivitySummary · Experience 를 다시 쓰지 않는다. 그 모양에 칸이 늘어도 여기로 새지 않게

    public record PublicProfileView(String title, List<PublicActivity> activities) {
    }

    public record PublicActivity(String name, Period period, ActivityCategory category, CategoryBy categoryBy,
            List<PublicExperience> experiences) {

        static PublicActivity of(Activity a, List<PublicExperience> experiences) {
            return new PublicActivity(a.getName(), a.getPeriod(), a.getCategory(), a.getCategoryBy(), experiences);
        }
    }

    public record PublicExperience(String content, SourceStatus sourceStatus, List<Competency> competencies,
            CompetenciesBy competenciesBy, List<String> sourceNames) {

        static PublicExperience of(Experience e, List<String> sourceNames) {
            return new PublicExperience(e.getContent(), e.getSourceStatus(), e.getCompetencies(),
                    e.getCompetenciesBy(), sourceNames);
        }
    }
}

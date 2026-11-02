package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.experience.ExperienceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final ExperienceService experiences;

    public ActivityService(ActivityRepository activities, ExperienceService experiences) {
        this.activities = activities;
        this.experiences = experiences;
    }

    @Transactional(readOnly = true)
    public ActivityResponses.Timeline timeline() {
        return new ActivityResponses.Timeline(activities.findTimeline(CurrentUser.id()).stream()
                .map(ActivityResponses.Summary::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public ActivityResponses.Detail detail(long activityId) {
        return ActivityResponses.Detail.from(find(activityId));
    }

    /** 보낸 필드만 바꾼다. period 는 세 칸을 통째로 받는다(계약 「PATCH /api/activities」). */
    @Transactional
    public ActivityResponses.Summary update(long activityId, PatchBody body) {
        Activity activity = find(activityId);

        body.string("name").ifPresent(name -> {
            if (name.value() == null || name.value().isBlank()) {
                throw ApiException.invalidRequest("name 이 비었습니다");
            }
            activity.rename(name.value());
        });

        body.object("period").ifPresent(field -> {
            PatchBody period = field.value();
            if (period == null) {
                throw ApiException.invalidRequest("period 는 start · end · ongoing 을 담은 객체여야 합니다");
            }
            Boolean ongoing = period.bool("ongoing").map(PatchBody.Field::value).orElse(null);
            if (ongoing == null) {
                throw ApiException.invalidRequest("period.ongoing 이 없습니다");
            }
            String start = period.string("start").map(PatchBody.Field::value).orElse(null);
            String end = period.string("end").map(PatchBody.Field::value).orElse(null);
            activity.changePeriod(Period.validated(start, end, ongoing));
        });

        activities.flush();
        return ActivityResponses.Summary.from(activity);
    }

    /**
     * from 활동의 경험을 전부 남길 활동 맨 뒤로 옮기고 from 을 지운다 — 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다.
     * 남길 활동의 이름 · 기간, 경험 · 근거는 그대로다(계약 「활동 합치기」 · S-13).
     */
    @Transactional
    public ActivityResponses.Detail merge(long activityId, PatchBody body) {
        Long fromActivityId = body.id("fromActivityId").map(PatchBody.Field::value).orElse(null);
        if (fromActivityId == null) {
            throw ApiException.invalidRequest("fromActivityId 가 없습니다");
        }
        Activity keep = find(activityId);
        if (fromActivityId == activityId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACTIVITY", "같은 활동끼리는 합칠 수 없습니다");
        }
        Activity from = find(fromActivityId);

        experiences.moveToEnd(from.getExperiences(), keep);
        // Hibernate 는 경험 UPDATE 를 활동 DELETE 보다 먼저 내보낸다 — 외래 키가 걸리지 않는다
        activities.delete(from);
        activities.flush();
        return ActivityResponses.Detail.from(keep);
    }

    private Activity find(long activityId) {
        return activities.findByIdAndUserId(activityId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));
    }
}

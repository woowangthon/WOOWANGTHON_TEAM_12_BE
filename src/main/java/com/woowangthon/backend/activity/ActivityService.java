package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private final ActivityRepository activities;

    public ActivityService(ActivityRepository activities) {
        this.activities = activities;
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

    private Activity find(long activityId) {
        return activities.findByIdAndUserId(activityId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));
    }
}

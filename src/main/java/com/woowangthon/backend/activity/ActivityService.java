package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.experience.ExperienceService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        List<Activity> all = activities.findTimeline(CurrentUser.id());
        Map<Long, Activity> byId = all.stream().collect(Collectors.toMap(Activity::getId, Function.identity()));
        return new ActivityResponses.Timeline(all.stream()
                .map(a -> ActivityResponses.Summary.from(a, suggestedFrom(a, byId::get)))
                .toList());
    }

    @Transactional(readOnly = true)
    public ActivityResponses.Detail detail(long activityId) {
        return detail(find(activityId));
    }

    /** MCP 도구 — 사용자를 직접 받고, mergeSuggestion 을 싣지 않는다(mcp-api.md). */
    @Transactional(readOnly = true)
    public ActivityResponses.McpTimeline mcpTimeline(long userId) {
        return new ActivityResponses.McpTimeline(activities.findTimeline(userId).stream()
                .map(ActivityResponses.McpSummary::from)
                .toList());
    }

    /** MCP 도구 — 남의 활동은 없는 것과 같이 ACTIVITY_NOT_FOUND. mergeSuggestion 을 싣지 않는다. */
    @Transactional(readOnly = true)
    public ActivityResponses.McpDetail mcpDetail(long userId, long activityId) {
        return ActivityResponses.McpDetail.from(find(userId, activityId));
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
        return ActivityResponses.Summary.from(activity, suggestedFrom(activity, this::sameUser));
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
        // 같은 활동 제안(S-44): from 에 붙은 제안은 from 과 같이 사라지고, from 을 가리키던 제안은 null.
        // 남길 활동의 제안은 그대로 — from 을 가리키고 있었으면 null(벌크 UPDATE 는 관리 중인 keep 을 고치지 않는다)
        if (fromActivityId.equals(keep.getMergeSuggestionActivityId())) {
            keep.dismissMergeSuggestion();
        }
        activities.clearMergeSuggestionsTo(fromActivityId);
        // Hibernate 는 경험 UPDATE 를 활동 DELETE 보다 먼저 내보낸다 — 외래 키가 걸리지 않는다
        activities.delete(from);
        activities.flush();
        return detail(keep);
    }

    /** 따로 두기(S-44) — 제안만 null 로. 두 활동은 그대로고, 이미 없어도 된다. */
    @Transactional
    public void dismissMergeSuggestion(long activityId) {
        find(activityId).dismissMergeSuggestion();
    }

    private ActivityResponses.Detail detail(Activity activity) {
        return ActivityResponses.Detail.from(activity, suggestedFrom(activity, this::sameUser));
    }

    private Activity sameUser(Long activityId) {
        return activities.findByIdAndUserId(activityId, CurrentUser.id()).orElse(null);
    }

    // 제안이 가리키는 활동(지금 값). 없거나 사라졌으면 null
    private static Activity suggestedFrom(Activity activity, Function<Long, Activity> lookup) {
        Long target = activity.getMergeSuggestionActivityId();
        return target == null ? null : lookup.apply(target);
    }

    private Activity find(long activityId) {
        return find(CurrentUser.id(), activityId);
    }

    private Activity find(long userId, long activityId) {
        return activities.findByIdAndUserId(activityId, userId)
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));
    }
}

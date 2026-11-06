package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceService;
import com.woowangthon.backend.question.RecommendationRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private static final int NOTE_MAX_LENGTH = 200;

    private final ActivityRepository activities;
    private final ExperienceService experiences;
    private final RecommendationRepository recommendations;
    private final ActivityLinkService links;
    private final ActivityLinkRepository linkRows;

    public ActivityService(ActivityRepository activities, ExperienceService experiences,
            RecommendationRepository recommendations, ActivityLinkService links, ActivityLinkRepository linkRows) {
        this.activities = activities;
        this.experiences = experiences;
        this.recommendations = recommendations;
        this.links = links;
        this.linkRows = linkRows;
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

        body.string("note").ifPresent(note -> activity.changeNote(validNote(note.value())));
        body.string("category").ifPresent(category -> activity.chooseCategory(parseCategory(category.value())));

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
        // 계기(S-34): 남길 활동 것을 그대로 두고, 없을 때만 옮겨 온다 — 이어 붙이지 않는다
        if (keep.getNote() == null) {
            keep.changeNote(from.getNote());
        }
        // 분류(S-57): 남길 활동 것을 그대로 두고, 미분류일 때만 옮겨 온다
        keep.takeCategoryIfNone(from);
        // 같은 활동 제안(S-44): from 에 붙은 제안은 from 과 같이 사라지고, from 을 가리키던 제안은 null.
        // 남길 활동의 제안은 그대로 — from 을 가리키고 있었으면 null(벌크 UPDATE 는 관리 중인 keep 을 고치지 않는다)
        if (fromActivityId.equals(keep.getMergeSuggestionActivityId())) {
            keep.dismissMergeSuggestion();
        }
        activities.clearMergeSuggestionsTo(fromActivityId);
        links.moveOnMerge(from, keep);
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

    /** 자료 넣기 되돌리기(S-54) — 활동에 경험 · 계기 · 이어짐이 하나도 없으면 지운다. 합치기로 이미 없어졌으면 그냥 넘어간다. */
    @Transactional
    public void removeIfBare(long userId, long activityId) {
        activities.findByIdAndUserId(activityId, userId)
                .filter(activity -> activity.getExperiences().isEmpty() && activity.getNote() == null
                        && !linkRows.existsByFromActivityIdOrToActivityId(activityId, activityId))
                .ifPresent(activity -> {
                    activities.clearMergeSuggestionsTo(activityId);
                    activities.delete(activity);
                    activities.flush();
                });
    }

    // 앞뒤 공백을 걷은 뒤 확인한다. null · 빈 문자열은 지움(계약 「PATCH /api/activities」)
    // null 이면 비우기. 목록 밖 값은 400
    private static ActivityCategory parseCategory(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ActivityCategory.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("category 는 PROJECT · STUDY · CLUB 중 하나여야 합니다: " + value);
        }
    }

    private static String validNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String stripped = note.strip();
        if (stripped.length() > NOTE_MAX_LENGTH) {
            throw ApiException.invalidRequest("note 는 " + NOTE_MAX_LENGTH + "자까지입니다");
        }
        if (stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0) {
            throw ApiException.invalidRequest("note 에 줄바꿈을 넣을 수 없습니다");
        }
        return stripped;
    }

    private ActivityResponses.Detail detail(Activity activity) {
        return ActivityResponses.Detail.from(activity, suggestedFrom(activity, this::sameUser), usedIn(activity),
                links.visibleOn(activity));
    }

    // 쓰인 지원(S-38) — 경험 수와 상관없이 한 번에 읽는다
    private Map<Long, List<ActivityResponses.UsedIn>> usedIn(Activity activity) {
        List<Long> experienceIds = activity.getExperiences().stream().map(Experience::getId).toList();
        if (experienceIds.isEmpty()) {
            return Map.of();
        }
        return ActivityResponses.UsedIn.byExperience(recommendations.findUsedIn(activity.getUserId(), experienceIds));
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

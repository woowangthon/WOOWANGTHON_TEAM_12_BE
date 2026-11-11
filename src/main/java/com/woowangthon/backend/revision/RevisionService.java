package com.woowangthon.backend.revision;

import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.ActivityService;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.ExperienceService;
import com.woowangthon.backend.question.AnswerDraftService;
import com.woowangthon.backend.question.QuestionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수정 이력 읽기 · 되돌리기(계약 revision-api.md · S-80). 남기는 쪽은 RevisionRecorder 다 — 이 서비스는 로그인한 사용자의 이력만 본다.
 * 남의 이력 · 항목은 없는 것과 같이 404. AI 를 부르지 않고 분석 횟수를 쓰지 않는다.
 */
@Service
public class RevisionService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    // 처음 쪽을 읽을 때 커서 대신 넣는 아주 먼 미래 — (createdAt, id) 가 이보다 앞인 것 전부
    private static final OffsetDateTime FAR_FUTURE = OffsetDateTime.of(9999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

    private final RevisionRepository revisions;
    private final ExperienceRepository experiences;
    private final ActivityRepository activities;
    private final QuestionRepository questions;
    private final ExperienceService experienceService;
    private final ActivityService activityService;
    private final AnswerDraftService answerDrafts;
    private final JsonMapper json;

    public RevisionService(RevisionRepository revisions, ExperienceRepository experiences,
            ActivityRepository activities, QuestionRepository questions, ExperienceService experienceService,
            ActivityService activityService, AnswerDraftService answerDrafts, JsonMapper json) {
        this.revisions = revisions;
        this.experiences = experiences;
        this.activities = activities;
        this.questions = questions;
        this.experienceService = experienceService;
        this.activityService = activityService;
        this.answerDrafts = answerDrafts;
        this.json = json;
    }

    /** 지운 항목이면 deleted — 그 밖의 칸은 기록한 때 값이다. */
    public record TargetView(boolean deleted, Long activityId, String activityName, Long questionId,
            String questionText, Long applicationId, String organization) {
    }

    public record RevisionView(Long id, RevisionTargetType targetType, Long targetId, RevisionKind kind,
            RevisionBy by, String client, Long mcpChangeId, Long restoredFromId, OffsetDateTime createdAt,
            TargetView target, JsonNode before, JsonNode after) {
    }

    public record Page(List<RevisionView> revisions, String nextCursor) {
    }

    /** 대상에 맞는 칸 하나만 차고 나머지는 null. */
    public record RestoreResult(RevisionView revision, ExperienceResponse experience,
            ActivityService.ActivityBrief activity, AnswerDraftService.AnswerDraftView answerDraft) {
    }

    // to — BEFORE(그 수정 전 값) · AFTER(그때 값). 본문이 없거나 빼면 BEFORE
    public record RestoreRequest(String to) {
    }

    // 다음 쪽의 시작점 — FE 는 해석하지 않는다
    private record Cursor(String at, long id) {
    }

    /** 새것 먼저. targetType · targetId 를 둘 다 주면 그 항목만, activityId 면 그 활동과 지금 속한 경험(BE#168), 다 빼면 전체. */
    @Transactional(readOnly = true)
    public Page list(String targetType, Long targetId, Long activityId, String cursor, Integer limitParam) {
        long userId = CurrentUser.id();
        int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalidRequest("limit 은 1 ~ " + MAX_LIMIT + " 이어야 합니다");
        }
        if ((targetType == null) != (targetId == null)) {
            throw ApiException.invalidRequest("targetType 과 targetId 는 함께 보내야 합니다");
        }
        if (activityId != null && targetType != null) {
            throw ApiException.invalidRequest("activityId 는 targetType · targetId 와 함께 보낼 수 없습니다");
        }
        RevisionTargetType type = targetType == null ? null : parseType(targetType);
        Cursor from = cursor == null ? null : decode(cursor);
        if (type != null) {
            requireOwned(userId, type, targetId);
        }
        if (activityId != null) {
            requireOwned(userId, RevisionTargetType.ACTIVITY, activityId);
        }

        OffsetDateTime at = from == null ? FAR_FUTURE : Instant.parse(from.at()).atOffset(ZoneOffset.UTC);
        long id = from == null ? Long.MAX_VALUE : from.id();
        // 하나 더 읽어 다음 쪽이 있는지 안다
        List<Revision> found = activityId != null ? revisions.ofActivity(userId, activityId, at, id, Limit.of(limit + 1))
                : type == null ? revisions.recent(userId, at, id, Limit.of(limit + 1))
                : revisions.ofTarget(userId, type, targetId, at, id, Limit.of(limit + 1));
        boolean more = found.size() > limit;
        List<Revision> shown = more ? found.subList(0, limit) : found;

        Existing existing = existing(userId, shown);
        return new Page(shown.stream().map(r -> view(r, !existing.has(r))).toList(),
                more ? encode(shown.getLast()) : null);
    }

    /**
     * 고른 기록의 스냅샷으로 되돌리고 RESTORE 기록을 하나 더한다. 지난 기록은 건드리지 않는다.
     * to 가 AFTER 면 그 기록의 그때 값(after), BEFORE · null 이면 그 수정 전 값(before) — 화면의 줄은 after 를 보인다(FE#430).
     * 경험 · 활동은 바로 쓰고, 답은 확정 답을 바꾸지 않고 임시 글로 넣는다.
     */
    @Transactional
    public RestoreResult restore(long revisionId, String to) {
        if (to != null && !to.equals("BEFORE") && !to.equals("AFTER")) {
            throw ApiException.invalidRequest("to 는 BEFORE · AFTER 중 하나여야 합니다: " + to);
        }
        Revision source = revisions.findByIdAndUserId(revisionId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("REVISION_NOT_FOUND", "수정 이력 " + revisionId + " 이(가) 없습니다"));
        String snapshot = "AFTER".equals(to) ? source.getAfterSnapshot() : source.getBeforeSnapshot();
        return switch (source.getTargetType()) {
            case EXPERIENCE -> {
                Restored<ExperienceResponse> restored = experienceService.restore(source.getTargetId(),
                        json.readValue(snapshot, ExperienceSnapshot.class), source.getId());
                yield new RestoreResult(view(restored.revision(), false), restored.value(), null, null);
            }
            case ACTIVITY -> {
                Restored<ActivityService.ActivityBrief> restored = activityService.restore(source.getTargetId(),
                        json.readValue(snapshot, ActivitySnapshot.class), source.getId());
                yield new RestoreResult(view(restored.revision(), false), null, restored.value(), null);
            }
            case ANSWER -> {
                Restored<AnswerDraftService.AnswerDraftView> restored = answerDrafts.restore(source.getTargetId(),
                        json.readValue(snapshot, AnswerSnapshot.class).text(), source.getId());
                yield new RestoreResult(view(restored.revision(), false), null, null, restored.value());
            }
        };
    }

    // 항목만 볼 때 그 항목이 없거나 남의 것이면 404 — 지운 항목의 이력은 「최근 수정」에서만 보인다
    private void requireOwned(long userId, RevisionTargetType type, long targetId) {
        boolean found = switch (type) {
            case EXPERIENCE -> experiences.findOwned(targetId, userId).isPresent();
            case ACTIVITY -> activities.findByIdAndUserId(targetId, userId).isPresent();
            case ANSWER -> questions.findByIdAndUserId(targetId, userId).isPresent();
        };
        if (!found) {
            String code = switch (type) {
                case EXPERIENCE -> "EXPERIENCE_NOT_FOUND";
                case ACTIVITY -> "ACTIVITY_NOT_FOUND";
                case ANSWER -> "QUESTION_NOT_FOUND";
            };
            throw ApiException.notFound(code, "항목 " + targetId + " 이(가) 없습니다");
        }
    }

    // 목록의 대상이 지금도 있는지 — 한 줄씩 읽지 않고 종류마다 한 번에
    private record Existing(Set<Long> experiences, Set<Long> activities, Set<Long> questions) {

        boolean has(Revision r) {
            return switch (r.getTargetType()) {
                case EXPERIENCE -> experiences.contains(r.getTargetId());
                case ACTIVITY -> activities.contains(r.getTargetId());
                case ANSWER -> questions.contains(r.getTargetId());
            };
        }
    }

    private Existing existing(long userId, List<Revision> shown) {
        return new Existing(
                alive(shown, RevisionTargetType.EXPERIENCE, ids -> experiences.findOwnedIds(ids, userId)),
                alive(shown, RevisionTargetType.ACTIVITY, ids -> activities.findOwnedIds(ids, userId)),
                alive(shown, RevisionTargetType.ANSWER, ids -> questions.findOwnedIds(ids, userId)));
    }

    private static Set<Long> alive(List<Revision> shown, RevisionTargetType type,
            java.util.function.Function<Collection<Long>, List<Long>> lookup) {
        Set<Long> ids = shown.stream().filter(r -> r.getTargetType() == type).map(Revision::getTargetId)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Set.of() : new HashSet<>(lookup.apply(ids));
    }

    private RevisionView view(Revision r, boolean deleted) {
        TargetInfo t = r.getTarget();
        return new RevisionView(r.getId(), r.getTargetType(), r.getTargetId(), r.getKind(), r.getChangedBy(),
                r.getClient(), r.getMcpChangeId(), r.getRestoredFromId(), Times.seoul(r.getCreatedAt()),
                new TargetView(deleted, t.activityId(), t.activityName(), t.questionId(), t.questionText(),
                        t.applicationId(), t.organization()),
                json.readTree(r.getBeforeSnapshot()), json.readTree(r.getAfterSnapshot()));
    }

    private static RevisionTargetType parseType(String value) {
        try {
            return RevisionTargetType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("targetType 은 EXPERIENCE · ACTIVITY · ANSWER 중 하나여야 합니다: " + value);
        }
    }

    private String encode(Revision last) {
        Cursor cursor = new Cursor(last.getCreatedAt().toInstant().toString(), last.getId());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsString(cursor).getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String value) {
        try {
            Cursor cursor = json.readValue(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8),
                    Cursor.class);
            Instant.parse(cursor.at());
            return cursor;
        } catch (RuntimeException e) {
            throw ApiException.invalidRequest("cursor 를 읽을 수 없습니다");
        }
    }
}

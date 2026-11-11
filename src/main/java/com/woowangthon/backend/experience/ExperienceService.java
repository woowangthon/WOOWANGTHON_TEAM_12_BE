package com.woowangthon.backend.experience;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.revision.ExperienceSnapshot;
import com.woowangthon.backend.revision.Restored;
import com.woowangthon.backend.revision.Revision;
import com.woowangthon.backend.revision.RevisionErrors;
import com.woowangthon.backend.revision.RevisionKind;
import com.woowangthon.backend.revision.RevisionOrigin;
import com.woowangthon.backend.revision.RevisionRecorder;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {

    private final ExperienceRepository experiences;
    private final CitationRepository citations;
    private final SupplementRepository supplements;
    private final ActivityRepository activities;
    private final RevisionRecorder recorder;
    private final ExperienceMerger merger;
    private final EntityManager em;

    public ExperienceService(ExperienceRepository experiences, CitationRepository citations,
            SupplementRepository supplements, ActivityRepository activities, RevisionRecorder recorder,
            ExperienceMerger merger, EntityManager em) {
        this.merger = merger;
        this.em = em;
        this.experiences = experiences;
        this.citations = citations;
        this.supplements = supplements;
        this.activities = activities;
        this.recorder = recorder;
    }

    /** 보낸 필드만 바꾼다. 값이 실제로 바뀌면 USER_ENTERED, 근거는 그대로(계약 「PATCH /api/experiences」 · S-10). */
    @Transactional
    public ExperienceResponse update(long experienceId, PatchBody body) {
        Experience experience = findOwned(experienceId);

        String content = body.string("content").map(field -> {
            if (field.value() == null || field.value().isBlank()) {
                throw ApiException.invalidRequest("content 가 비었습니다");
            }
            return field.value();
        }).orElse(null);

        // 보내면 통째로 바꾼다. [] 는 지우기, null 은 400
        List<Competency> competencies = body.list("competencies").map(field -> parseCompetencies(field.value()))
                .orElse(null);

        Actor actor = body.string("actor").map(field -> parseActor(field.value())).orElse(null);

        ExperienceSnapshot before = ExperienceSnapshot.of(experience);
        experience.editByUser(content, competencies, actor);
        experiences.flush();
        // 스냅샷이 하나도 안 바뀌었으면 남기지 않는다(S-80)
        recorder.experience(experience, before, RevisionKind.EDIT, RevisionOrigin.USER, null);
        return ExperienceResponse.from(experience);
    }

    /**
     * 수정 이력의 되돌리기(S-80) — 그 수정 전 스냅샷으로 통째로 쓰고 RESTORE 기록을 더한다. 행을 잠가 외부 AI 고치기와 겹치지 않게 한다.
     * 이미 그 값이면 409, 지운 경험이면 409(복구는 범위 밖).
     */
    @Transactional
    public Restored<ExperienceResponse> restore(long experienceId, ExperienceSnapshot target, long revisionId) {
        Experience experience = experiences.findOwnedForUpdate(experienceId, CurrentUser.id())
                .orElseThrow(RevisionErrors::targetDeleted);
        ExperienceSnapshot before = ExperienceSnapshot.of(experience);
        if (before.equals(target)) {
            throw RevisionErrors.nothingToRestore();
        }
        experience.restoreSnapshot(target.content(), target.actor(), target.competencies(), target.competenciesBy(),
                target.sourceStatus());
        experiences.flush();
        Revision revision = recorder.experience(experience, before, RevisionKind.RESTORE, RevisionOrigin.USER,
                revisionId);
        return new Restored<>(ExperienceResponse.from(experience), revision);
    }

    /**
     * 사용자가 직접 적은 경험 — 출처는 「내가 입력」, 근거는 없다. 활동의 경험 맨 뒤에 붙는다(계약 「경험 직접 추가」 · S-11).
     * competencies 는 선택(빼면 []), 역량 출처는 USER.
     */
    @Transactional
    public ExperienceResponse add(long activityId, PatchBody body) {
        return ExperienceResponse.from(create(activityId, body, null));
    }

    /**
     * 「경험 직접 추가」와 같은 경험을 만든다 — 확정 답의 후보 저장(S-48)도 쓴다. origin 이 있으면 그 문항에서 왔다고 표시한다.
     * 호출하는 쪽 트랜잭션 안에서 부른다.
     */
    public Experience create(long activityId, PatchBody body, Question origin) {
        Activity activity = activities.findByIdAndUserId(activityId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));

        String content = body.string("content").map(PatchBody.Field::value).orElse(null);
        if (content == null || content.isBlank()) {
            throw ApiException.invalidRequest("content 가 비었습니다");
        }
        List<Competency> competencies = body.list("competencies").map(field -> parseCompetencies(field.value()))
                .orElse(List.of());
        Actor actor = parseActor(body.string("actor").map(PatchBody.Field::value).orElse(null));

        int position = experiences.findLastPosition(activityId) + 1;
        Experience created = new Experience(activity, content, competencies, CompetenciesBy.USER, actor,
                SourceStatus.USER_ENTERED, position);
        if (origin != null) {
            created.markFromAnswer(origin);
        }
        Experience experience = experiences.saveAndFlush(created);
        // 같은 요청 · 같은 영속성 컨텍스트에서 활동 세부를 다시 읽어도 보이게
        if (Hibernate.isInitialized(activity.getExperiences())) {
            activity.getExperiences().add(experience);
        }
        return experience;
    }

    /** 프로필에 넣기 · 빼기(share-api.md 「PUT .../shared」). 빼기는 언제나 되고, 넣기는 「확인 필요」면 막는다. */
    @Transactional
    public ExperienceResponse setShared(long experienceId, PatchBody body) {
        Boolean shared = body.bool("shared").map(PatchBody.Field::value).orElse(null);
        if (shared == null) {
            throw ApiException.invalidRequest("shared 가 없습니다");
        }
        // 외부 AI 고치기가 같이 들어와도 지금 출처 상태로 정하게 잠근다
        Experience experience = experiences.findOwnedForUpdate(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));
        if (shared && experience.getSourceStatus() == SourceStatus.NEEDS_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPERIENCE_NEEDS_REVIEW", "확인이 필요한 경험은 넣을 수 없습니다");
        }
        experience.setShared(shared);
        experiences.flush();
        return ExperienceResponse.from(experience);
    }

    /** 저장 · 빼기(계약 「PUT .../saved」). 경험 수정과 달리 출처 상태 · 역량 출처를 바꾸지 않는다(S-71). */
    @Transactional
    public ExperienceResponse setSaved(long experienceId, PatchBody body) {
        Boolean saved = body.bool("saved").map(PatchBody.Field::value).orElse(null);
        if (saved == null) {
            throw ApiException.invalidRequest("saved 가 없습니다");
        }
        // 행 전체를 다시 쓰므로 외부 AI 고치기가 같이 들어와도 그 값을 덮지 않게 잠근다
        Experience experience = experiences.findOwnedForUpdate(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));
        experience.setSaved(saved);
        experiences.flush();
        return ExperienceResponse.from(experience);
    }

    /** 경험과 그 근거 · 보완을 지운다. 원본과 활동은 남는다(계약 「경험 지우기」 · S-11). */
    @Transactional
    public void delete(long experienceId) {
        remove(findOwned(experienceId));
        experiences.flush();
    }

    /** 활동 지우기(S-88) — 그 활동의 경험마다 경험 지우기와 같은 길로 지운다. 활동은 부르는 쪽이 지운다. */
    @Transactional
    public void deleteAllOf(Activity activity) {
        for (Experience experience : List.copyOf(activity.getExperiences())) {
            remove(experience);
        }
        experiences.flush();
    }

    private void remove(Experience experience) {
        citations.deleteAll(experience.getCitations());
        supplements.deleteAll(experience.getSupplements());
        experiences.delete(experience);
        if (Hibernate.isInitialized(experience.getActivity().getExperiences())) {
            experience.getActivity().getExperiences().remove(experience);
        }
    }

    /** 경험이 속한 활동만 바꾼다. 내용 · 근거 · 출처 상태는 그대로, 옮긴 활동의 맨 뒤에 붙는다(계약 「경험 옮기기」 · S-12). */
    @Transactional
    public ExperienceResponse move(long experienceId, PatchBody body) {
        Long toActivityId = body.id("toActivityId").map(PatchBody.Field::value).orElse(null);
        if (toActivityId == null) {
            throw ApiException.invalidRequest("toActivityId 가 없습니다");
        }
        Experience experience = findOwned(experienceId);
        Activity target = activities.findByIdAndUserId(toActivityId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + toActivityId + " 이(가) 없습니다"));

        // 이미 그 활동에 있으면 아무것도 바꾸지 않는다
        if (!experience.getActivity().getId().equals(target.getId())) {
            moveToEnd(List.of(experience), target);
            experiences.flush();
        }
        return ExperienceResponse.from(experience);
    }

    /**
     * 같은 활동의 경험 둘을 하나로 — from 의 근거 · 보완을 남길 경험으로 옮기고, from 을 가리키던 것도 남길 경험으로 바꾼 뒤
     * from 을 지운다. 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다. 남길 경험의 값은 그대로이고 저장만 둘 중 하나라도
     * 켜져 있으면 켠다(계약 「경험 합치기」 · S-70 · S-71).
     */
    @Transactional
    public ExperienceResponse merge(long keepId, PatchBody body) {
        Long fromId = body.id("fromExperienceId").map(PatchBody.Field::value).orElse(null);
        if (fromId == null) {
            throw ApiException.invalidRequest("fromExperienceId 가 없습니다");
        }
        if (fromId == keepId) {
            throw ApiException.invalidRequest("같은 경험끼리는 합칠 수 없습니다");
        }
        // 서로 반대로 합치는 요청끼리 맞물리지 않게 id 가 작은 쪽부터 잠근다
        Experience keep;
        Experience from;
        if (keepId < fromId) {
            keep = lockOwned(keepId);
            from = lockOwned(fromId);
        } else {
            from = lockOwned(fromId);
            keep = lockOwned(keepId);
        }
        if (!keep.getActivity().getId().equals(from.getActivity().getId())) {
            throw ApiException.invalidRequest("다른 활동의 경험은 합칠 수 없습니다 — 먼저 같은 활동으로 옮기세요");
        }

        if (from.isSaved() && !keep.isSaved()) {
            keep.setSaved(true);
        }
        experiences.flush();
        merger.repoint(keepId, fromId);
        // 근거 · 보완은 이미 keep 으로 옮겼다 — 지울 때 같이 지워지지 않게 SQL 로 from 한 줄만 지운다
        // 지울 경험의 STAR 칸은 같이 사라진다(외래 키가 없어 직접 지운다)
        em.createNativeQuery("delete from experience_star_slot where experience_id = :id").setParameter("id", fromId)
                .executeUpdate();
        em.createNativeQuery("delete from experience where id = :id").setParameter("id", fromId).executeUpdate();
        // SQL 로 바뀐 것을 다시 읽는다
        em.clear();
        // 남길 경험의 STAR 칸은 그대로 — 늘어난 근거 · 보완으로 다시 정리하지 않는다(ADR-019). 정리 전(NONE)이면 StarSweeper 가 정리한다
        Experience merged = findOwned(keepId);
        return ExperienceResponse.from(merged);
    }

    private Experience lockOwned(long experienceId) {
        return experiences.findOwnedForUpdate(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));
    }

    /** 경험들을 받은 순서대로 활동의 맨 뒤로 옮긴다(옮기기 · 합치기가 같이 쓴다). */
    public void moveToEnd(List<Experience> moving, Activity target) {
        int position = experiences.findLastPosition(target.getId());
        for (Experience experience : List.copyOf(moving)) {
            Activity from = experience.getActivity();
            experience.moveTo(target, ++position);
            // 같은 요청 · 같은 영속성 컨텍스트에서 두 활동 세부를 다시 읽어도 보이게
            if (Hibernate.isInitialized(from.getExperiences())) {
                from.getExperiences().remove(experience);
            }
            if (Hibernate.isInitialized(target.getExperiences())) {
                target.getExperiences().add(experience);
            }
        }
    }

    private Experience findOwned(long experienceId) {
        return experiences.findOwned(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));
    }

    private static List<Competency> parseCompetencies(List<?> values) {
        if (values == null) {
            throw ApiException.invalidRequest("competencies 는 null 일 수 없습니다 — 지우려면 [] 를 보내세요");
        }
        try {
            return Competency.parseStrict(values);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest(e.getMessage());
        }
    }

    private static Actor parseActor(String value) {
        if (value != null) {
            for (Actor actor : Actor.values()) {
                if (actor.name().equals(value)) {
                    return actor;
                }
            }
        }
        throw ApiException.invalidRequest("actor 는 ME · TEAM · UNKNOWN 중 하나여야 합니다");
    }
}

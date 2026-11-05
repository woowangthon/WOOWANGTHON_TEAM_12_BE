package com.woowangthon.backend.experience;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.question.Question;
import java.util.List;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {

    private final ExperienceRepository experiences;
    private final CitationRepository citations;
    private final SupplementRepository supplements;
    private final ActivityRepository activities;

    public ExperienceService(ExperienceRepository experiences, CitationRepository citations,
            SupplementRepository supplements, ActivityRepository activities) {
        this.experiences = experiences;
        this.citations = citations;
        this.supplements = supplements;
        this.activities = activities;
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

        experience.editByUser(content, competencies, actor);
        experiences.flush();
        return ExperienceResponse.from(experience);
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

    /** 경험과 그 근거 · 보완을 지운다. 원본과 활동은 남는다(계약 「경험 지우기」 · S-11). */
    @Transactional
    public void delete(long experienceId) {
        Experience experience = findOwned(experienceId);
        citations.deleteAll(experience.getCitations());
        supplements.deleteAll(experience.getSupplements());
        experiences.delete(experience);
        if (Hibernate.isInitialized(experience.getActivity().getExperiences())) {
            experience.getActivity().getExperiences().remove(experience);
        }
        experiences.flush();
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

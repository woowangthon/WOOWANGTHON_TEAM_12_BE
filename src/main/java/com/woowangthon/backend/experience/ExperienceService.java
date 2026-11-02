package com.woowangthon.backend.experience;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import java.util.Optional;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {

    private final ExperienceRepository experiences;
    private final CitationRepository citations;
    private final ActivityRepository activities;

    public ExperienceService(ExperienceRepository experiences, CitationRepository citations,
            ActivityRepository activities) {
        this.experiences = experiences;
        this.citations = citations;
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

        // perspective 는 null 로 지울 수 있다(직접 추가 API 와 같이 null 허용)
        Optional<PatchBody.Field<String>> perspective = body.string("perspective");

        Actor actor = body.string("actor").map(field -> parseActor(field.value())).orElse(null);

        experience.editByUser(content, perspective.isPresent(), perspective.map(PatchBody.Field::value).orElse(null),
                actor);
        experiences.flush();
        return ExperienceResponse.from(experience);
    }

    /**
     * 사용자가 직접 적은 경험 — 출처는 「내가 입력」, 근거는 없다. 활동의 경험 맨 뒤에 붙는다(계약 「경험 직접 추가」 · S-11).
     * perspective 는 선택(빼면 null). competencies 로 바뀌는 건 BE#15.
     */
    @Transactional
    public ExperienceResponse add(long activityId, PatchBody body) {
        Activity activity = activities.findByIdAndUserId(activityId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));

        String content = body.string("content").map(PatchBody.Field::value).orElse(null);
        if (content == null || content.isBlank()) {
            throw ApiException.invalidRequest("content 가 비었습니다");
        }
        String perspective = body.string("perspective").map(PatchBody.Field::value).orElse(null);
        Actor actor = parseActor(body.string("actor").map(PatchBody.Field::value).orElse(null));

        int position = experiences.findLastPosition(activityId) + 1;
        Experience experience = experiences.saveAndFlush(
                new Experience(activity, content, perspective, actor, SourceStatus.USER_ENTERED, position));
        // 같은 요청 · 같은 영속성 컨텍스트에서 활동 세부를 다시 읽어도 보이게
        if (Hibernate.isInitialized(activity.getExperiences())) {
            activity.getExperiences().add(experience);
        }
        return ExperienceResponse.from(experience);
    }

    /** 경험과 그 근거를 지운다. 원본과 활동은 남는다(계약 「경험 지우기」 · S-11). */
    @Transactional
    public void delete(long experienceId) {
        Experience experience = findOwned(experienceId);
        citations.deleteAll(experience.getCitations());
        experiences.delete(experience);
        if (Hibernate.isInitialized(experience.getActivity().getExperiences())) {
            experience.getActivity().getExperiences().remove(experience);
        }
        experiences.flush();
    }

    private Experience findOwned(long experienceId) {
        return experiences.findOwned(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));
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

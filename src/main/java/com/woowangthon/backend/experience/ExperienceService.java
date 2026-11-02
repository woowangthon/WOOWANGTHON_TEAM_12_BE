package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {

    private final ExperienceRepository experiences;

    public ExperienceService(ExperienceRepository experiences) {
        this.experiences = experiences;
    }

    /** 보낸 필드만 바꾼다. 값이 실제로 바뀌면 USER_ENTERED, 근거는 그대로(계약 「PATCH /api/experiences」 · S-10). */
    @Transactional
    public ExperienceResponse update(long experienceId, PatchBody body) {
        Experience experience = experiences.findOwned(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));

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

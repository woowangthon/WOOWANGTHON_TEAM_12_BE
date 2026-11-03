package com.woowangthon.backend.question;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 되묻기에 답하기 — 답을 그 경험의 보완으로 저장한다(question-api.md 「POST /api/clarifications/{id}/answer」 · S-23).
 * 경험의 본문 · 근거 · 출처 상태는 건드리지 않는다(SPEC 미결정 10). AI 를 부르지 않으므로 하루 한도를 세지 않는다.
 */
@Service
public class ClarificationService {

    private final ClarificationRepository clarifications;
    private final ExperienceRepository experiences;
    private final SupplementRepository supplements;

    public ClarificationService(ClarificationRepository clarifications, ExperienceRepository experiences,
            SupplementRepository supplements) {
        this.clarifications = clarifications;
        this.experiences = experiences;
        this.supplements = supplements;
    }

    public record AnswerRequest(String answer) {
    }

    public record Answered(Long experienceId, ExperienceResponse.SupplementResponse supplement) {
    }

    @Transactional
    public Answered answer(long clarificationId, AnswerRequest request) {
        String answer = Supplement.checkAnswer(request == null ? null : request.answer());
        long userId = CurrentUser.id();
        Clarification clarification = clarifications.findOwned(clarificationId, userId)
                .orElseThrow(() -> ApiException.notFound("CLARIFICATION_NOT_FOUND",
                        "되묻기 " + clarificationId + " 이(가) 없습니다"));
        Experience experience = experiences.findOwned(clarification.getExperienceId(), userId)
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "되묻기 " + clarificationId + " 이(가) 묻던 경험이 지워졌습니다"));
        if (supplements.existsByClarificationId(clarificationId)) {
            throw alreadyAnswered(clarificationId);
        }

        Supplement supplement;
        try {
            supplement = supplements.saveAndFlush(new Supplement(experience, clarificationId,
                    clarification.getQuestion().getId(), clarification.getText(), answer));
        } catch (DataIntegrityViolationException e) {
            // 같은 되묻기에 동시에 두 번 답한 경우 — clarification_id unique 가 막는다
            throw alreadyAnswered(clarificationId);
        }
        // 같은 요청 · 같은 영속성 컨텍스트에서 경험을 다시 읽어도 보이게
        if (Hibernate.isInitialized(experience.getSupplements())) {
            experience.getSupplements().add(supplement);
        }
        return new Answered(experience.getId(), ExperienceResponse.SupplementResponse.from(supplement));
    }

    private static ApiException alreadyAnswered(long clarificationId) {
        return new ApiException(HttpStatus.CONFLICT, "CLARIFICATION_ALREADY_ANSWERED",
                "되묻기 " + clarificationId + " 에는 이미 답했습니다");
    }
}

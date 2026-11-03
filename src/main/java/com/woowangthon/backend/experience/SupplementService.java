package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보완 고치기 · 지우기(계약 experience-api.md 「PATCH · DELETE /api/supplements/{id}」 · S-24).
 * 경험의 본문 · 근거 · 출처 상태는 건드리지 않는다(SPEC 미결정 10).
 */
@Service
public class SupplementService {

    private final SupplementRepository supplements;

    public SupplementService(SupplementRepository supplements) {
        this.supplements = supplements;
    }

    public record AnswerRequest(String answer) {
    }

    @Transactional
    public ExperienceResponse.SupplementResponse update(long supplementId, AnswerRequest request) {
        String answer = Supplement.checkAnswer(request == null ? null : request.answer());
        Supplement supplement = findOwned(supplementId);
        supplement.changeAnswer(answer);
        // 응답의 updatedAt 이 바뀐 시각이 되게 지금 저장한다
        supplements.flush();
        return ExperienceResponse.SupplementResponse.from(supplement);
    }

    /**
     * 지우면 그 경험의 보완 목록 · 다음 매칭 후보에서 빠지고, 그 보완을 만든 되묻기는 다시 답할 수 있다(둘 다 지금 보완으로 계산한다).
     * 이미 끝난 추천의 근거에 남은 이 보완 번호는 응답을 만들 때 뺀다(QuestionResponses).
     */
    @Transactional
    public void delete(long supplementId) {
        Supplement supplement = findOwned(supplementId);
        Experience experience = supplement.getExperience();
        supplements.delete(supplement);
        // 같은 요청 · 같은 영속성 컨텍스트에서 경험을 다시 읽어도 안 보이게
        if (Hibernate.isInitialized(experience.getSupplements())) {
            experience.getSupplements().remove(supplement);
        }
        supplements.flush();
    }

    private Supplement findOwned(long supplementId) {
        return supplements.findOwned(supplementId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("SUPPLEMENT_NOT_FOUND",
                        "보완 " + supplementId + " 이(가) 없습니다"));
    }
}

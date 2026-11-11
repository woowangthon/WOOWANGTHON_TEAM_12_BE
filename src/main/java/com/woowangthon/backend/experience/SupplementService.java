package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import java.util.List;
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
        Supplement supplement = supplements.findOwnedForUpdate(supplementId, CurrentUser.id())
                .orElseThrow(() -> notFound(supplementId));
        String before = supplement.getAnswer();
        supplement.changeAnswer(answer);
        // STAR 칸이 가리키던 보완이면 칸이 새 답을 따르거나 「다시 정리」가 된다(계약 「다른 동작과의 관계」)
        supplement.getExperience().onSupplementChanged(supplementId, before, answer, null);
        // 고친 답이 칸 밖에도 있을 수 있다 — 「다시 나누기」를 보이게(BE#184)
        supplement.getExperience().markStarNew();
        // 응답의 updatedAt 이 바뀐 시각이 되게 지금 저장한다
        supplements.flush();
        return ExperienceResponse.SupplementResponse.from(supplement);
    }

    /**
     * 지우면 그 경험의 보완 목록 · 다음 매칭 후보에서 빠지고, 그 보완을 만든 되묻기는 다시 답할 수 있다(둘 다 지금 보완으로 계산한다).
     * 이미 끝난 추천의 근거에서 이 보완을 가리키는 줄도 지우고 남은 줄을 다시 매긴다(BE#37).
     * 이 정리 전에 남은 줄이 있을 수 있어 응답을 만들 때도 거른다(QuestionResponses).
     */
    @Transactional
    public void delete(long supplementId) {
        delete(CurrentUser.id(), supplementId);
    }

    /** 변경 기록의 되돌리기도 같은 코드로 지운다(S-56). */
    @Transactional
    public void delete(long userId, long supplementId) {
        Supplement supplement = supplements.findOwned(supplementId, userId).orElseThrow(() -> notFound(supplementId));
        Experience experience = supplement.getExperience();
        // 그 보완을 가리키던 STAR 칸은 빈칸이 되고 경험은 「다시 정리」
        experience.onSupplementDeleted(supplementId);
        supplements.delete(supplement);
        // 같은 요청 · 같은 영속성 컨텍스트에서 경험을 다시 읽어도 안 보이게
        if (Hibernate.isInitialized(experience.getSupplements())) {
            experience.getSupplements().remove(supplement);
        }
        supplements.flush();
        deleteEvidenceCiting(supplementId);
    }

    private void deleteEvidenceCiting(long supplementId) {
        List<Long> recommendationIds = supplements.findRecommendationIdsCiting(supplementId);
        if (recommendationIds.isEmpty()) {
            return;
        }
        supplements.deleteEvidenceCiting(supplementId);
        supplements.moveEvidenceToNegativePositions(recommendationIds);
        supplements.restoreEvidencePositions(recommendationIds);
    }

    private static ApiException notFound(long supplementId) {
        return ApiException.notFound("SUPPLEMENT_NOT_FOUND", "보완 " + supplementId + " 이(가) 없습니다");
    }
}

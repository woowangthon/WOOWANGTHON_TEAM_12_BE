package com.woowangthon.backend.experience;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SupplementRepository extends JpaRepository<Supplement, Long> {

    boolean existsByClarificationId(Long clarificationId);

    List<Supplement> findByClarificationIdIn(Collection<Long> clarificationIds);

    // 보완 → 경험 → 활동의 주인으로 확인한다 — 남의 보완은 없는 것과 같다
    @Query("select s from Supplement s join fetch s.experience e join fetch e.activity a "
            + "where s.id = :id and a.userId = :userId")
    Optional<Supplement> findOwned(Long id, Long userId);

    // ── 추천 근거 줄 정리(BE#37) ───────────────────────────────────────────────
    // 근거 줄의 ref_id 는 근거 · 보완 번호를 함께 담아 외래 키가 없다 — 보완을 지울 때 여기서 지운다.
    // 근거 목록은 @OrderColumn(position) 이라 번호가 비면 읽을 때 null 이 끼므로 남은 줄을 0부터 다시 매긴다.
    // PK (recommendation_id, position) 가 순간 겹치지 않게 음수로 한 번 옮겼다가(-1, -2, …) 되돌린다.

    @Query(value = "select distinct recommendation_id from question_recommendation_evidence "
            + "where type = 'SUPPLEMENT' and ref_id = :supplementId", nativeQuery = true)
    List<Long> findRecommendationIdsCiting(Long supplementId);

    @Modifying(flushAutomatically = true)
    @Query(value = "delete from question_recommendation_evidence where type = 'SUPPLEMENT' and ref_id = :supplementId",
            nativeQuery = true)
    void deleteEvidenceCiting(Long supplementId);

    @Modifying
    @Query(value = """
            update question_recommendation_evidence e set position = -1 - n.rn
            from (select recommendation_id, position,
                         row_number() over (partition by recommendation_id order by position) - 1 as rn
                  from question_recommendation_evidence where recommendation_id in (:recommendationIds)) n
            where e.recommendation_id = n.recommendation_id and e.position = n.position
            """, nativeQuery = true)
    void moveEvidenceToNegativePositions(List<Long> recommendationIds);

    // 이 요청은 추천을 불러오지 않지만, 혹시 불러온 게 있으면 옛 목록을 들고 있지 않게 비운다
    @Modifying(clearAutomatically = true)
    @Query(value = "update question_recommendation_evidence set position = -1 - position "
            + "where recommendation_id in (:recommendationIds) and position < 0", nativeQuery = true)
    void restoreEvidencePositions(List<Long> recommendationIds);
}

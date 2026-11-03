package com.woowangthon.backend.experience;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SupplementRepository extends JpaRepository<Supplement, Long> {

    boolean existsByClarificationId(Long clarificationId);

    // 보완 → 경험 → 활동의 주인으로 확인한다 — 남의 보완은 없는 것과 같다
    @Query("select s from Supplement s join fetch s.experience e join fetch e.activity a "
            + "where s.id = :id and a.userId = :userId")
    Optional<Supplement> findOwned(Long id, Long userId);
}

package com.woowangthon.backend.experience;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    @Query("select e from Experience e join fetch e.activity a where e.id = :id and a.userId = :userId")
    Optional<Experience> findOwned(Long id, Long userId);

    // 활동의 경험 맨 뒤 자리 — 경험이 없으면 -1
    @Query("select coalesce(max(e.position), -1) from Experience e where e.activity.id = :activityId")
    int findLastPosition(Long activityId);
}

package com.woowangthon.backend.experience;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    @Query("select e from Experience e join fetch e.activity a where e.id = :id and a.userId = :userId")
    Optional<Experience> findOwned(Long id, Long userId);

    @Query("select e from Experience e join fetch e.activity a where e.id in :ids and a.userId = :userId")
    List<Experience> findAllOwned(Collection<Long> ids, Long userId);

    // 사용자의 경험 전부 — 타임라인 순서(활동 start 오름차순, null 은 뒤) · 활동 안 순서
    @Query("select e from Experience e join fetch e.activity a where a.userId = :userId "
            + "order by a.periodStart asc nulls last, a.id asc, e.position asc, e.id asc")
    List<Experience> findAllOfUser(Long userId);

    // 활동의 경험 맨 뒤 자리 — 경험이 없으면 -1
    @Query("select coalesce(max(e.position), -1) from Experience e where e.activity.id = :activityId")
    int findLastPosition(Long activityId);
}

package com.woowangthon.backend.activity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    // 타임라인 순서: start 오름차순, start 가 null 이면 맨 뒤(계약 「GET /api/activities」)
    @Query("select a from Activity a where a.userId = :userId order by a.periodStart asc nulls last, a.id asc")
    List<Activity> findTimeline(Long userId);

    Optional<Activity> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserId(Long userId);
}

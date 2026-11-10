package com.woowangthon.backend.activity;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    // 타임라인 순서: start 오름차순, start 가 null 이면 맨 뒤(계약 「GET /api/activities」)
    @Query("select a from Activity a where a.userId = :userId order by a.periodStart asc nulls last, a.id asc")
    List<Activity> findTimeline(Long userId);

    Optional<Activity> findByIdAndUserId(Long id, Long userId);

    // 수정 이력 되돌리기(S-80) — 같이 고치는 요청과 겹쳐 서로 덮지 않게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.id = :id and a.userId = :userId")
    Optional<Activity> findOwnedForUpdate(Long id, Long userId);

    // 수정 이력 목록에서 지금도 있는 활동만(S-80)
    @Query("select a.id from Activity a where a.id in :ids and a.userId = :userId")
    List<Long> findOwnedIds(Collection<Long> ids, Long userId);

    boolean existsByUserId(Long userId);

    // /extract 의 existingActivities: start 최근 순, start 가 null 이면 뒤(ai-api.md — 최대 300개)
    @Query("select a from Activity a where a.userId = :userId order by a.periodStart desc nulls last, a.id desc")
    List<Activity> findRecent(Long userId, Limit limit);

    // 합치기로 사라지는 활동을 가리키던 제안을 null 로(S-44). 다른 활동으로 옮겨 가리키지 않는다
    @Modifying(flushAutomatically = true)
    @Query("update Activity a set a.mergeSuggestionActivityId = null where a.mergeSuggestionActivityId = :activityId")
    void clearMergeSuggestionsTo(Long activityId);
}

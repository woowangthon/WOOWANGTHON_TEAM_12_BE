package com.woowangthon.backend.experience;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    @Query("select e from Experience e join fetch e.activity a where e.id = :id and a.userId = :userId")
    Optional<Experience> findOwned(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Experience e where e.id = :id and e.activity.userId = :userId")
    Optional<Experience> findOwnedForUpdate(Long id, Long userId);

    @Query("select e from Experience e join fetch e.activity a where e.id in :ids and a.userId = :userId")
    List<Experience> findAllOwned(Collection<Long> ids, Long userId);

    // 수정 이력 목록에서 지금도 있는 경험만(S-80)
    @Query("select e.id from Experience e where e.id in :ids and e.activity.userId = :userId")
    List<Long> findOwnedIds(Collection<Long> ids, Long userId);

    // 사용자의 경험 전부 — 타임라인 순서(활동 start 오름차순, null 은 뒤) · 활동 안 순서
    @Query("select e from Experience e join fetch e.activity a where a.userId = :userId "
            + "order by a.periodStart asc nulls last, a.id asc, e.position asc, e.id asc")
    List<Experience> findAllOfUser(Long userId);

    // 초안의 보조 재료 후보(S-75) — 그 활동들의 경험, 최근에 생긴 것부터
    @Query("select e from Experience e join fetch e.activity a where a.id in :activityIds and a.userId = :userId "
            + "order by e.createdAt desc, e.id desc")
    List<Experience> findRecentInActivities(Collection<Long> activityIds, Long userId);

    // 공개 조건 —고른 경험 중 「확인 필요」가 아닌 것(share-api.md). 순서는 타임라인 · 활동 세부와 같다
    @Query("select e from Experience e join fetch e.activity a where a.userId = :userId and e.shared = true "
            + "and e.sourceStatus <> com.woowangthon.backend.experience.SourceStatus.NEEDS_REVIEW "
            + "order by a.periodStart asc nulls last, a.id asc, e.position asc, e.id asc")
    List<Experience> findVisible(Long userId);

    @Query("select count(e) from Experience e where e.activity.userId = :userId and e.shared = true")
    long countShared(Long userId);

    @Query("select count(e) from Experience e where e.activity.userId = :userId and e.shared = true "
            + "and e.sourceStatus <> com.woowangthon.backend.experience.SourceStatus.NEEDS_REVIEW")
    long countVisible(Long userId);

    // 활동의 경험 맨 뒤 자리 — 경험이 없으면 -1
    @Query("select coalesce(max(e.position), -1) from Experience e where e.activity.id = :activityId")
    int findLastPosition(Long activityId);
}

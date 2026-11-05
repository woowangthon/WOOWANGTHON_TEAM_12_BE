package com.woowangthon.backend.activity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ActivityLinkRepository extends JpaRepository<ActivityLink, Long> {

    // 남의 이어짐은 없는 것과 같다(계약 404 LINK_NOT_FOUND)
    @Query("""
            select l from ActivityLink l join fetch l.fromActivity join fetch l.toActivity
            where l.id = :id and l.fromActivity.userId = :userId""")
    Optional<ActivityLink> findOwned(Long id, Long userId);

    // 이 활동이 from 이든 to 든 걸린 이어짐 전부(상태 무관), createdAt 순
    @Query("""
            select l from ActivityLink l join fetch l.fromActivity join fetch l.toActivity
            where l.fromActivity.id = :activityId or l.toActivity.id = :activityId
            order by l.createdAt asc, l.id asc""")
    List<ActivityLink> findTouching(Long activityId);

    Optional<ActivityLink> findByFromActivityIdAndToActivityId(Long fromActivityId, Long toActivityId);

    // 그새 같은 쌍이 생겼으면(두 번 눌렀을 때) 아무것도 넣지 않고 빈 값
    @Query(value = """
            insert into activity_link (from_activity_id, to_activity_id, reason, status)
            values (:fromActivityId, :toActivityId, :reason, 'SUGGESTED')
            on conflict (from_activity_id, to_activity_id) do nothing
            returning id""", nativeQuery = true)
    Optional<Long> insertSuggested(Long fromActivityId, Long toActivityId, String reason);
}

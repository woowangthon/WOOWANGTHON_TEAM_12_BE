package com.woowangthon.backend.revision;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RevisionRepository extends JpaRepository<Revision, Long> {

    Optional<Revision> findByIdAndUserId(Long id, Long userId);

    // 새것 먼저 — (createdAt, id) 내림차순에서 (at, id) 보다 뒤인 것. 처음 쪽은 at 에 아주 먼 미래를 넣는다
    @Query("select r from Revision r where r.userId = :userId "
            + "and (r.createdAt < :at or (r.createdAt = :at and r.id < :id)) "
            + "order by r.createdAt desc, r.id desc")
    List<Revision> recent(Long userId, OffsetDateTime at, Long id, Limit limit);

    @Query("select r from Revision r where r.userId = :userId and r.targetType = :type and r.targetId = :targetId "
            + "and (r.createdAt < :at or (r.createdAt = :at and r.id < :id)) "
            + "order by r.createdAt desc, r.id desc")
    List<Revision> ofTarget(Long userId, RevisionTargetType type, Long targetId, OffsetDateTime at, Long id,
            Limit limit);

    // 활동 메뉴의 수정 이력(S-80 · BE#168) — 그 활동의 기록 + 지금 그 활동에 속한 경험의 기록. 옮긴 경험은 옮겨 간 활동에서 보인다
    @Query("select r from Revision r where r.userId = :userId and ("
            + "(r.targetType = com.woowangthon.backend.revision.RevisionTargetType.ACTIVITY and r.targetId = :activityId) "
            + "or (r.targetType = com.woowangthon.backend.revision.RevisionTargetType.EXPERIENCE and r.targetId in "
            + "(select e.id from Experience e where e.activity.id = :activityId))) "
            + "and (r.createdAt < :at or (r.createdAt = :at and r.id < :id)) "
            + "order by r.createdAt desc, r.id desc")
    List<Revision> ofActivity(Long userId, Long activityId, OffsetDateTime at, Long id, Limit limit);
}

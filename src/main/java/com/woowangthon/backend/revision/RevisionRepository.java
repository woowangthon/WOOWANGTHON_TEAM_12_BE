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
}

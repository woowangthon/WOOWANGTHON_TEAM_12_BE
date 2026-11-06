package com.woowangthon.backend.mcp;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface McpChangeRepository extends JpaRepository<McpChange, Long> {

    long countByUserIdAndTypeAndCreatedAtGreaterThanEqual(Long userId, McpChangeType type, OffsetDateTime from);

    List<McpChange> findTop100ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    // 되돌리기 · 맞아요를 두 번 눌러도 한 번만 일어나게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from McpChange c where c.id = :id and c.userId = :userId")
    Optional<McpChange> findOwnedForUpdate(Long id, Long userId);

    // 이 변경 바로 앞에서 아직 정하지 않은 고치기
    Optional<McpChange> findFirstByExperienceIdAndStatusAndIdLessThanOrderByIdDesc(Long experienceId,
            McpChangeStatus status, Long id);

    @Query("select distinct c.experienceId from McpChange c where c.experienceId in :experienceIds")
    List<Long> findExperienceIdsWithChange(Collection<Long> experienceIds);
}

package com.woowangthon.backend.chagok;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChagokRunRepository extends JpaRepository<ChagokRun, Long> {

    Optional<ChagokRun> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndStatus(Long userId, ChagokStatus status);

    List<ChagokRun> findByStatus(ChagokStatus status);

    // 확인 · 그만두기를 두 번 눌러도, 스트림과 겹쳐도 한 번만 상태가 바뀌게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChagokRun r where r.id = :id and r.userId = :userId")
    Optional<ChagokRun> findForUpdate(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChagokRun r where r.id = :id")
    Optional<ChagokRun> lockById(Long id);
}

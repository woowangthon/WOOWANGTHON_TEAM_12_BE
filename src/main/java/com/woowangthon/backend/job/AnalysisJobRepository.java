package com.woowangthon.backend.job;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByIdAndUserId(Long id, Long userId);

    // 「다시 시도」를 두 번 눌러도 분석이 한 번만 돌게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AnalysisJob j where j.id = :id and j.userId = :userId")
    Optional<AnalysisJob> findForUpdate(Long id, Long userId);

    List<AnalysisJob> findByStatus(JobStatus status);
}

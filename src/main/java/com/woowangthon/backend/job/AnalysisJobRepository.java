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

    @Query("select distinct j.userId from AnalysisJob j where j.status = com.woowangthon.backend.job.JobStatus.QUEUED")
    List<Long> findUsersWithQueued();

    // 사용자의 분석이 하나도 돌지 않을 때만 가장 먼저 만든 QUEUED 를 시작한다. 묶음이 겹쳐도 묶음 안 순서가 지켜진다.
    // 작업 둘이 같이 끝나도 한 문장이라 하나만 시작된다 — 늦은 쪽은 status 를 다시 보고 0행이 된다
    @Query(value = """
            update analysis_job set status = 'ANALYZING', updated_at = now()
            where id = (select min(id) from analysis_job where user_id = :userId and status = 'QUEUED')
              and status = 'QUEUED'
              and not exists (select 1 from analysis_job where user_id = :userId and status = 'ANALYZING')
            returning id
            """, nativeQuery = true)
    Optional<Long> startNextQueued(Long userId);
}

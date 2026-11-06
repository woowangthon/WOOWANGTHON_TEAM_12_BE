package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GroundingCheckRepository extends JpaRepository<GroundingCheck, Long> {

    Optional<GroundingCheck> findByQuestionId(Long questionId);

    // 결과를 싣는 동안 다른 쪽(재시작 정리)이 같은 행을 바꾸지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GroundingCheck g where g.questionId = :questionId")
    Optional<GroundingCheck> lockByQuestionId(Long questionId);

    List<GroundingCheck> findByStatus(JobStatus status);

    @Query("select g.questionId from GroundingCheck g where g.questionId in :questionIds")
    List<Long> findQuestionIds(Collection<Long> questionIds);
}

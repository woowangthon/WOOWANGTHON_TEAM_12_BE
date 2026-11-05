package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AnswerRepository extends JpaRepository<Answer, Long> {

    Optional<Answer> findByQuestionId(Long questionId);

    // 결과를 싣는 동안 다른 쪽(재시작 정리)이 같은 행을 바꾸지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Answer a where a.questionId = :questionId")
    Optional<Answer> lockByQuestionId(Long questionId);

    List<Answer> findByNewFactsStatus(JobStatus status);
}

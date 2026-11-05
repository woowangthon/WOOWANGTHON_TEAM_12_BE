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

    /**
     * 비슷한 확정 답변(S-43)의 후보 — 그 사용자의 확정 답 전부와 그 문항 · 지원. 기준 문항 자신의 답은 뺀다(같은 지원의 다른 문항은 넣는다).
     * 한 줄은 [Answer, Question, Application].
     */
    @Query("select a, q, ap from Answer a join Question q on q.id = a.questionId join q.application ap "
            + "where q.userId = :userId and a.questionId <> :questionId")
    List<Object[]> findConfirmedOfUserExcept(Long userId, Long questionId);
}

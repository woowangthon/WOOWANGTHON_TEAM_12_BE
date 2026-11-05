package com.woowangthon.backend.question;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NewFactCandidateRepository extends JpaRepository<NewFactCandidate, Long> {

    // 후보가 속한 문항 — 그 사용자의 것만. 문항 행을 먼저 잠그려고 따로 읽는다
    @Query("select a.questionId from NewFactCandidate c join c.answer a, Question q "
            + "where c.id = :id and q.id = a.questionId and q.userId = :userId")
    Optional<Long> findQuestionId(Long id, Long userId);
}

package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Optional<Question> findByIdAndUserId(Long id, Long userId);

    // 최근에 넣은 것부터(계약 「GET /api/questions」)
    List<Question> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    // 「다시 시도」를 두 번 눌러도 추천이 한 번만 돌게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id and q.userId = :userId")
    Optional<Question> findForUpdate(Long id, Long userId);

    List<Question> findByStatus(JobStatus status);

    long countByApplicationId(Long applicationId);
}

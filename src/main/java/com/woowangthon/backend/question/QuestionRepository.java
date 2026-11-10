package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Optional<Question> findByIdAndUserId(Long id, Long userId);

    // 수정 이력 목록에서 지금도 있는 문항만(S-80)
    @Query("select q.id from Question q where q.id in :ids and q.userId = :userId")
    List<Long> findOwnedIds(Collection<Long> ids, Long userId);

    // 최근에 넣은 것부터(계약 「GET /api/questions」)
    List<Question> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    // 「다시 시도」를 두 번 눌러도 추천이 한 번만 돌게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id and q.userId = :userId")
    Optional<Question> findForUpdate(Long id, Long userId);

    // 매칭 결과를 싣는 동안 사용자가 고른 유형(PATCH)을 덮어쓰지 않게 행을 잠근다(S-41)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id")
    Optional<Question> lockById(Long id);

    // 외부 AI 가 만든 지원을 되돌리는 동안 다시 시도 · 초안이 끼어들지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.application.id = :applicationId order by q.id")
    List<Question> lockByApplicationId(Long applicationId);

    List<Question> findByStatus(JobStatus status);

    long countByApplicationId(Long applicationId);
}

package com.woowangthon.backend.question;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByIdAndUserId(Long id, Long userId);

    // 최근에 넣은 것부터(계약 「GET /api/applications」)
    List<Application> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    // 문항 더하기를 동시에 눌러도 지원 하나의 문항이 한도(20개)를 넘지 않게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = :id and a.userId = :userId")
    Optional<Application> findForUpdate(Long id, Long userId);
}

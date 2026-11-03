package com.woowangthon.backend.question;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClarificationRepository extends JpaRepository<Clarification, Long> {

    // 다른 사용자의 되묻기는 없는 것으로 본다
    @Query("select c from Clarification c join fetch c.question q where c.id = :id and q.userId = :userId")
    Optional<Clarification> findOwned(Long id, Long userId);
}

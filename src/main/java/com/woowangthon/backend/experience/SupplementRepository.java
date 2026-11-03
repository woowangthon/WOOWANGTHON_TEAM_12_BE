package com.woowangthon.backend.experience;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplementRepository extends JpaRepository<Supplement, Long> {

    boolean existsByClarificationId(Long clarificationId);
}

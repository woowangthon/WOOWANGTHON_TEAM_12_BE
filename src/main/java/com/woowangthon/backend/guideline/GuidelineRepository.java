package com.woowangthon.backend.guideline;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuidelineRepository extends JpaRepository<Guideline, Long> {

    List<Guideline> findByUserIdOrderByIdAsc(Long userId);

    List<Guideline> findByUserIdAndEnabledTrueOrderByIdAsc(Long userId);

    Optional<Guideline> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndEnabledTrue(Long userId);
}

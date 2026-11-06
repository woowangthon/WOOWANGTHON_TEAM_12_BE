package com.woowangthon.backend.experience;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CitationRepository extends JpaRepository<Citation, Long> {

    interface ExperienceCount {
        Long getSourceId();

        Long getExperienceCount();
    }

    // 이 원본을 근거로 한 경험
    @Query("select distinct c.experience from Citation c where c.source.id = :sourceId")
    List<Experience> findExperiencesOfSource(Long sourceId);

    // 원본마다 그것을 근거로 한 경험 수 — 하나도 없는 원본은 안 나온다
    @Query("""
            select c.source.id as sourceId, count(distinct c.experience.id) as experienceCount
            from Citation c where c.source.id in :sourceIds group by c.source.id""")
    List<ExperienceCount> countExperiencesBySource(Collection<Long> sourceIds);
}

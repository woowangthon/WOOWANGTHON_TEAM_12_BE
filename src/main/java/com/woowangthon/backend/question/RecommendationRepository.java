package com.woowangthon.backend.question;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 경험이 지금 추천에 있는 지원 · 문항 한 줄(S-38). */
    record UsedInRow(Long experienceId, Long applicationId, String organization, ApplicationStatus applicationStatus,
            String submittedMonth, Long questionId, String questionText) {
    }

    // 쓰인 지원(S-38): 지원은 최근에 넣은 것부터, 문항은 지원서 순서(계약 「쓰인 지원 usedIn」). 경험 수만큼 나가지 않게 한 번에 읽는다
    @Query("""
            select new com.woowangthon.backend.question.RecommendationRepository$UsedInRow(
                r.experienceId, a.id, a.organization, a.applicationStatus, a.submittedMonth, q.id, q.text)
            from Recommendation r join r.question q join q.application a
            where q.userId = :userId and r.experienceId in :experienceIds
            order by a.createdAt desc, a.id desc, q.id asc""")
    List<UsedInRow> findUsedIn(Long userId, Collection<Long> experienceIds);
}

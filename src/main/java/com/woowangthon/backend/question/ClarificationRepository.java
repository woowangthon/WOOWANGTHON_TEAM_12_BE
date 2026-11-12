package com.woowangthon.backend.question;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClarificationRepository extends JpaRepository<Clarification, Long> {

    // 다른 사용자의 되묻기는 없는 것으로 본다
    // 문항 되묻기는 문항, 공고 되묻기는 그 줄의 지원으로 주인을 본다
    @Query("select c from Clarification c left join c.question q left join c.postingLine l left join l.application a "
            + "where c.id = :id and (q.userId = :userId or a.userId = :userId)")
    Optional<Clarification> findOwned(Long id, Long userId);

    // 지원의 공고 되묻기 — 넣은 순서
    @Query("select c from Clarification c join c.postingLine l where l.application.id = :applicationId order by c.position")
    List<Clarification> findByApplicationPosting(Long applicationId);

    // 공고 기준 경험 찾기를 다시 돌릴 때 — 답한 보완은 clarification_id 만 null 이 되고 남는다
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from Clarification c where c.postingLine.id in "
            + "(select l.id from ApplicationPostingLine l where l.application.id = :applicationId)")
    void deleteByApplicationPosting(Long applicationId);

    // 같은 지원서의 다른 문항에서 아직 답하지 않은 되묻기. 생성 시각 칸이 없어 증가하는 ID 로 최근 순서를 본다.
    // 공고 되묻기는 question 이 없어 빠진다. 보완이 지워지면 다시 미답변으로 포함된다(S-24).
    @Query("select c from Clarification c join c.question q "
            + "where q.application.id = :applicationId and q.userId = :userId "
            + "and (:excludedQuestionId is null or q.id <> :excludedQuestionId) "
            + "and not exists (select 1 from Supplement s where s.clarificationId = c.id) order by c.id desc")
    List<Clarification> findUnansweredInOtherQuestions(Long applicationId, Long userId, Long excludedQuestionId);

    // 되묻기에 답이 있는 문항 — 보완을 지우면 답이 없는 것으로 돌아간다(S-24)
    @Query("select distinct c.question.id from Clarification c where c.question.id in :questionIds "
            + "and exists (select 1 from Supplement s where s.clarificationId = c.id)")
    List<Long> findAnsweredQuestionIds(Collection<Long> questionIds);

    // 초안을 다시 만들 때 예전 초안의 되묻기를 무효로 — 답한 보완은 clarification_id 만 null 이 되고 남는다
    @Modifying(flushAutomatically = true)
    @Query("delete from Clarification c where c.draftId = :draftId")
    void deleteByDraftId(Long draftId);
}

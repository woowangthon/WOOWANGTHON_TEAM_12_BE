package com.woowangthon.backend.question;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClarificationRepository extends JpaRepository<Clarification, Long> {

    // 다른 사용자의 되묻기는 없는 것으로 본다
    @Query("select c from Clarification c join fetch c.question q where c.id = :id and q.userId = :userId")
    Optional<Clarification> findOwned(Long id, Long userId);

    // 되묻기에 답이 있는 문항 — 보완을 지우면 답이 없는 것으로 돌아간다(S-24)
    @Query("select distinct c.question.id from Clarification c where c.question.id in :questionIds "
            + "and exists (select 1 from Supplement s where s.clarificationId = c.id)")
    List<Long> findAnsweredQuestionIds(Collection<Long> questionIds);

    // 초안을 다시 만들 때 예전 초안의 되묻기를 무효로 — 답한 보완은 clarification_id 만 null 이 되고 남는다
    @Modifying(flushAutomatically = true)
    @Query("delete from Clarification c where c.draftId = :draftId")
    void deleteByDraftId(Long draftId);
}

package com.woowangthon.backend.question;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AnswerDraftRepository extends JpaRepository<AnswerDraft, Long> {

    Optional<AnswerDraft> findByQuestionId(Long questionId);

    @Modifying
    @Query("delete from AnswerDraft d where d.questionId = :questionId")
    void deleteByQuestionId(Long questionId);
}

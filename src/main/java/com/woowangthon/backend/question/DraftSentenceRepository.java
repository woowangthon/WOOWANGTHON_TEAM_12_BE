package com.woowangthon.backend.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DraftSentenceRepository extends JpaRepository<DraftSentence, Long> {

    // 그 사용자의 문항에 딸린 초안 문장만 — 바꾼 줄 수(0 이면 없거나 남의 것이거나 다시 만들어 사라졌다)
    @Modifying
    @Query("update DraftSentence s set s.confirmed = true where s.id = :id and s.draft.id in "
            + "(select d.id from Draft d, Question q where d.questionId = q.id and q.userId = :userId)")
    int confirmOwned(Long id, Long userId);

    // 빼기 · 다시 넣기 — 바꾼 줄 수(0 이면 없거나 남의 것이거나 다시 만들어 사라졌다)
    @Modifying
    @Query("update DraftSentence s set s.removed = :removed where s.id = :id and s.draft.id in "
            + "(select d.id from Draft d, Question q where d.questionId = q.id and q.userId = :userId)")
    int setRemovedOwned(Long id, Long userId, boolean removed);
}

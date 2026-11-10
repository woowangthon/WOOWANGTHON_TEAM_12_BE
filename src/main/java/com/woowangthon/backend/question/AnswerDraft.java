package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 쓰는 중인 답 — 문항마다 하나(question-api.md 「임시 글」 · S-72). 확정 답변이 아니다 — 어떤 AI 요청 · 후보 찾기 · 비슷한 답에도 쓰지 않는다.
 * state 는 FE 가 정하는 화면 상태 JSON 글이고 BE 는 해석하지 않는다.
 */
@Entity
@Table(name = "answer_draft")
public class AnswerDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long questionId;

    @Column(nullable = false)
    private String text;

    private String state;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected AnswerDraft() {
    }

    public AnswerDraft(Long questionId) {
        this.questionId = questionId;
    }

    void write(String newText, String newState) {
        text = newText;
        state = newState;
        updatedAt = OffsetDateTime.now();
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getText() {
        return text;
    }

    public String getState() {
        return state;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

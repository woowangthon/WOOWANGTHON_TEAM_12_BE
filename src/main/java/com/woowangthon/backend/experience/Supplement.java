package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.http.HttpStatus;

/**
 * 보완 — 되묻기 질문과 사용자의 답 한 쌍. 그 경험에 딸리고 늘 「내가 입력」이다. 경험의 본문 · 근거 · 출처 상태는 바꾸지 않는다
 * (SPEC 미결정 10).
 */
@Entity
@Table(name = "supplement")
public class Supplement {

    public static final int MAX_ANSWER_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experience_id")
    private Experience experience;

    // 이 보완을 만든 되묻기 · 지원 문항 — 지워지면 null
    private Long clarificationId;

    private Long questionId;

    @Column(nullable = false)
    private String question;

    @Column(nullable = false)
    private String answer;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Supplement() {
    }

    public Supplement(Experience experience, Long clarificationId, Long questionId, String question, String answer) {
        this.experience = experience;
        this.clarificationId = clarificationId;
        this.questionId = questionId;
        this.question = question;
        this.answer = answer;
    }

    /**
     * 답의 규칙 — 되묻기에 답할 때 · 보완을 고칠 때 같이 쓴다(계약 「되묻기에 답하기」 · 「보완 고치기」).
     * 한도는 String.length()(UTF-16) 기준 — FE(JS length)와 같은 숫자.
     */
    public static String checkAnswer(String answer) {
        if (answer == null) {
            throw ApiException.invalidRequest("answer 가 없습니다");
        }
        if (answer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ANSWER", "answer 가 비었습니다");
        }
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw ApiException.invalidRequest("answer 는 " + MAX_ANSWER_LENGTH + "자까지입니다");
        }
        return answer;
    }

    /** 답만 고친다(checkAnswer 를 거친 값) — 질문은 되묻기 그대로, 경험의 출처 상태도 그대로(S-24). */
    void changeAnswer(String answer) {
        this.answer = answer;
    }

    Experience getExperience() {
        return experience;
    }

    public Long getId() {
        return id;
    }

    public Long getClarificationId() {
        return clarificationId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

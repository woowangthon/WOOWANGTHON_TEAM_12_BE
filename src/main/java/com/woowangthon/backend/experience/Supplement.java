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
 * 보완 — 되묻기 질문과 사용자의 답 한 쌍. 그 경험에 딸리고 「내가 입력」이다 — 외부 AI 가 전한 답은 사용자가 정할 때까지 아니다(S-56).
 * 경험의 본문 · 근거 · 출처 상태는 바꾸지 않는다(SPEC 미결정 10).
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

    // 외부 AI 가 전해 사용자가 아직 정하지 않았을 때만 찬다(S-56 · 계약 mcpChange). 찬 동안은 근거가 아니다
    private Long mcpChangeId;

    private String mcpClient;

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

    /** 답만 고친다(checkAnswer 를 거친 값) — 질문은 되묻기 그대로, 경험의 출처 상태도 그대로(S-24). 외부 AI 가 전한 답도 사용자 말이 된다(S-56). */
    void changeAnswer(String answer) {
        this.answer = answer;
        confirmExternalAiAnswer();
    }

    /** 외부 AI 가 전한 답이다 — 사용자가 맞아요를 누르거나 웹에서 고칠 때까지 근거가 아니다(S-56). */
    public void markFromExternalAi(long changeId, String client) {
        mcpChangeId = changeId;
        mcpClient = client;
    }

    /** 맞아요 — 다음 추천 · 초안부터 근거가 된다. */
    public void confirmExternalAiAnswer() {
        mcpChangeId = null;
        mcpClient = null;
    }

    /** 근거로 써도 되나 — 외부 AI 를 거쳐 아직 확인 전이면 아니다. */
    public boolean isConfirmed() {
        return mcpChangeId == null;
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

    public Long getMcpChangeId() {
        return mcpChangeId;
    }

    public String getMcpClient() {
        return mcpClient;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

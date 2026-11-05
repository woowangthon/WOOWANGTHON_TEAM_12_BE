package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 근거 점검 — 문항마다 최신 하나(question-api.md 「근거 점검」). 다시 점검하면 같은 행을 처음부터 다시 쓴다(createdAt 도).
 * 문항의 추천 작업과 따로 돈다.
 */
@Entity
@Table(name = "grounding_check")
public class GroundingCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long questionId;

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String errorCode;

    private String errorMessage;

    @OneToMany(mappedBy = "check", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<GroundingSentence> sentences = new ArrayList<>();

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected GroundingCheck() {
    }

    public GroundingCheck(Long questionId) {
        this.questionId = questionId;
    }

    /** 새 글로 점검을 시작한다 — 예전 결과 · 실패 이유를 지운다. */
    public void start(String newText) {
        sentences.clear();
        text = newText;
        status = JobStatus.ANALYZING;
        errorCode = null;
        errorMessage = null;
        createdAt = OffsetDateTime.now();
    }

    /** 문장들은 이 점검을 가리키게 만든 것이어야 한다. */
    public void complete(List<GroundingSentence> newSentences) {
        sentences.clear();
        sentences.addAll(newSentences);
        status = JobStatus.COMPLETED;
        errorCode = null;
        errorMessage = null;
    }

    public void fail(String code, String message) {
        sentences.clear();
        status = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getText() {
        return text;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<GroundingSentence> getSentences() {
        return sentences;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

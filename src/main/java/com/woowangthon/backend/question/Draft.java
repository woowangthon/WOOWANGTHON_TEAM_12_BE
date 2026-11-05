package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 답변 초안 — 문항마다 최신 하나(question-api.md 「답변 초안」). 다시 만들면 같은 행을 처음부터 다시 쓴다(createdAt 도).
 */
@Entity
@Table(name = "draft")
public class Draft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long questionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String errorCode;

    private String errorMessage;

    @ElementCollection
    @CollectionTable(name = "draft_experience", joinColumns = @JoinColumn(name = "draft_id"))
    @OrderColumn(name = "position")
    @Column(name = "experience_id", nullable = false)
    private List<Long> experienceIds = new ArrayList<>();

    @OneToMany(mappedBy = "draft", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<DraftSentence> sentences = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "draft_omitted", joinColumns = @JoinColumn(name = "draft_id"))
    @OrderColumn(name = "position")
    @Column(name = "text", nullable = false)
    private List<String> omitted = new ArrayList<>();

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Draft() {
    }

    public Draft(Long questionId) {
        this.questionId = questionId;
    }

    /** 고른 경험으로 새로 만든다 — 예전 결과 · 실패 이유를 지운다. */
    public void start(List<Long> chosen) {
        sentences.clear();
        omitted.clear();
        experienceIds.clear();
        experienceIds.addAll(chosen);
        status = JobStatus.ANALYZING;
        errorCode = null;
        errorMessage = null;
        createdAt = OffsetDateTime.now();
    }

    /** 문장들은 이 초안을 가리키게 만든 것이어야 한다. */
    public void complete(List<DraftSentence> newSentences, List<String> newOmitted) {
        sentences.clear();
        sentences.addAll(newSentences);
        omitted.clear();
        omitted.addAll(newOmitted);
        status = JobStatus.COMPLETED;
        errorCode = null;
        errorMessage = null;
    }

    public void fail(String code, String message) {
        sentences.clear();
        omitted.clear();
        status = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
    }

    public Long getQuestionId() {
        return questionId;
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

    public List<Long> getExperienceIds() {
        return experienceIds;
    }

    public List<DraftSentence> getSentences() {
        return sentences;
    }

    public List<String> getOmitted() {
        return omitted;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

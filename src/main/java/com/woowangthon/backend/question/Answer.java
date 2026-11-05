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
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 확정 답변 — 문항마다 최신 하나(question-api.md 「확정 답변」 · S-48). 다시 확정하면 같은 행을 다시 쓴다.
 * 새 경험 후보 찾기는 이 행에 붙는다 — newFactsStatus 가 null 이면 아직 시작하지 않았다. 「저장 안 함」 한 문장은 다시 확정해도 남는다.
 */
@Entity
@Table(name = "answer")
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long questionId;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private OffsetDateTime confirmedAt;

    @Enumerated(EnumType.STRING)
    private JobStatus newFactsStatus;

    private String errorCode;

    private String errorMessage;

    private OffsetDateTime newFactsCreatedAt;

    private OffsetDateTime newFactsUpdatedAt;

    @OneToMany(mappedBy = "answer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<NewFactCandidate> candidates = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "answer_dismissed", joinColumns = @JoinColumn(name = "answer_id"))
    @Column(name = "sentence", nullable = false)
    private Set<String> dismissed = new HashSet<>();

    protected Answer() {
    }

    public Answer(Long questionId) {
        this.questionId = questionId;
    }

    /** 새 글로 확정한다 — 예전 찾기와 후보를 지운다(만든 경험 · 「저장 안 함」 기억은 남는다). */
    public void confirm(String newText) {
        text = newText;
        confirmedAt = OffsetDateTime.now();
        candidates.clear();
        newFactsStatus = null;
        errorCode = null;
        errorMessage = null;
        newFactsCreatedAt = null;
        newFactsUpdatedAt = null;
    }

    /** 후보 찾기를 처음부터 시작한다 — 예전 후보를 지운다. */
    public void startFinding() {
        candidates.clear();
        newFactsStatus = JobStatus.ANALYZING;
        errorCode = null;
        errorMessage = null;
        newFactsCreatedAt = OffsetDateTime.now();
        newFactsUpdatedAt = newFactsCreatedAt;
    }

    /** 후보들은 이 답을 가리키게 만든 것이어야 한다. */
    public void complete(List<NewFactCandidate> found) {
        candidates.clear();
        candidates.addAll(found);
        newFactsStatus = JobStatus.COMPLETED;
        errorCode = null;
        errorMessage = null;
        newFactsUpdatedAt = OffsetDateTime.now();
    }

    public void fail(String code, String message) {
        candidates.clear();
        newFactsStatus = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
        newFactsUpdatedAt = OffsetDateTime.now();
    }

    void saveCandidate(NewFactCandidate candidate, Long experienceId) {
        candidate.save(experienceId);
        newFactsUpdatedAt = OffsetDateTime.now();
    }

    void dismissCandidate(NewFactCandidate candidate) {
        candidate.dismiss();
        dismissed.add(candidate.getSentence());
        newFactsUpdatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getText() {
        return text;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public JobStatus getNewFactsStatus() {
        return newFactsStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getNewFactsCreatedAt() {
        return newFactsCreatedAt;
    }

    public OffsetDateTime getNewFactsUpdatedAt() {
        return newFactsUpdatedAt;
    }

    public List<NewFactCandidate> getCandidates() {
        return candidates;
    }

    public Set<String> getDismissed() {
        return dismissed;
    }
}

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 지원 문항 — 지원처 이름(선택)과 문항 텍스트. 문항 하나 = 추천 작업 하나라 상태 · 실패 이유 · 결과(요구사항 · 추천 · 되묻기)가
 * 여기에 붙는다(question-api.md).
 */
@Entity
@Table(name = "question")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private String organization;

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String errorCode;

    private String errorMessage;

    @ElementCollection
    @CollectionTable(name = "question_requirement", joinColumns = @JoinColumn(name = "question_id"))
    @OrderColumn(name = "position")
    private List<Requirement> requirements = new ArrayList<>();

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<Recommendation> recommendations = new ArrayList<>();

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<Clarification> clarifications = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Question() {
    }

    public Question(Long userId, String organization, String text) {
        this.userId = userId;
        this.organization = organization;
        this.text = text;
        this.status = JobStatus.ANALYZING;
    }

    /** AI 결과를 싣는다 — 추천 · 되묻기는 이 문항을 가리키게 만든 것이어야 한다. */
    public void complete(List<Requirement> newRequirements, List<Recommendation> newRecommendations,
            List<Clarification> newClarifications) {
        clearResults();
        requirements.addAll(newRequirements);
        recommendations.addAll(newRecommendations);
        clarifications.addAll(newClarifications);
        status = JobStatus.COMPLETED;
        errorCode = null;
        errorMessage = null;
    }

    public void fail(String code, String message) {
        clearResults();
        status = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
    }

    /** 실패한 문항을 같은 문항으로 다시 돌린다. */
    public void restart() {
        clearResults();
        status = JobStatus.ANALYZING;
        errorCode = null;
        errorMessage = null;
    }

    private void clearResults() {
        requirements.clear();
        recommendations.clear();
        clarifications.clear();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOrganization() {
        return organization;
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

    public List<Requirement> getRequirements() {
        return requirements;
    }

    public List<Recommendation> getRecommendations() {
        return recommendations;
    }

    public List<Clarification> getClarifications() {
        return clarifications;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

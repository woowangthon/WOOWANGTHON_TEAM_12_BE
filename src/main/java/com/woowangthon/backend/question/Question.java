package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 지원 문항 — 늘 어느 지원(Application)에 딸린다. 지원처 이름은 그 지원의 것이다. 문항 하나 = 추천 작업 하나라 상태 · 실패 이유 · 결과(요구사항 · 추천 · 되묻기)가
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String errorCode;

    private String errorMessage;

    // 문항 유형(S-41) — 매칭이 붙이고(AI) 사용자가 고친다(USER). USER 면 매칭 결과가 덮어쓰지 않는다
    @Enumerated(EnumType.STRING)
    private QuestionType questionType;

    @Enumerated(EnumType.STRING)
    private QuestionTypeBy questionTypeBy;

    // 목표 글자 수(S-77) — 사용자가 정한다. 매칭 · 다시 시도와 상관없다
    private Integer targetLength;

    @ElementCollection
    @CollectionTable(name = "question_requirement", joinColumns = @JoinColumn(name = "question_id"))
    @OrderColumn(name = "position")
    private List<Requirement> requirements = new ArrayList<>();

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<Recommendation> recommendations = new ArrayList<>();

    // 추천의 되묻기만 — 초안에서 만든 것(draft_id)은 초안 쪽이 다룬다
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    @SQLRestriction("draft_id is null")
    private List<Clarification> clarifications = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    // 추천이 끝난 시각 — updatedAt 은 사용자가 유형을 고쳐도 바뀐다(work-api.md)
    private OffsetDateTime finishedAt;

    protected Question() {
    }

    public Question(Application application, String text) {
        this.userId = application.getUserId();
        this.application = application;
        this.text = text;
        this.status = JobStatus.ANALYZING;
    }

    /**
     * AI 결과를 싣는다 — 추천 · 되묻기는 이 문항을 가리키게 만든 것이어야 한다. AI 가 붙인 유형(없으면 null)은 사용자가 고르지 않았을 때만
     * 싣는다.
     */
    public void complete(QuestionType aiType, List<Requirement> newRequirements, List<Recommendation> newRecommendations,
            List<Clarification> newClarifications) {
        clearResults();
        if (questionTypeBy != QuestionTypeBy.USER) {
            questionType = aiType;
            questionTypeBy = aiType == null ? null : QuestionTypeBy.AI;
        }
        requirements.addAll(newRequirements);
        recommendations.addAll(newRecommendations);
        clarifications.addAll(newClarifications);
        status = JobStatus.COMPLETED;
        errorCode = null;
        errorMessage = null;
        finishedAt = OffsetDateTime.now();
    }

    public void fail(String code, String message) {
        clearResults();
        status = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
        finishedAt = OffsetDateTime.now();
    }

    /** 문항 글을 바꾸고 추천을 처음부터 다시 돌린다(S-89) — 옛 결과는 지운다. 유형 · 목표 글자 수는 그대로다. */
    public void changeText(String newText) {
        text = newText;
        restart();
    }

    /** 실패한 문항을 같은 문항으로 다시 돌린다. */
    public void restart() {
        clearResults();
        status = JobStatus.ANALYZING;
        errorCode = null;
        errorMessage = null;
        finishedAt = null;
    }

    /** 사용자가 문항 유형을 고른다 — 분석 중 · 실패여도 된다. 그 뒤 매칭 결과가 덮어쓰지 않는다(S-41). */
    public void chooseType(QuestionType type) {
        questionType = type;
        questionTypeBy = QuestionTypeBy.USER;
    }

    /** 목표 글자 수를 정하거나(100 ~ 5000 — 부르는 쪽이 확인한다) null 로 비운다. */
    public void setTargetLength(Integer length) {
        targetLength = length;
    }

    // 결과와 함께 AI 가 붙인 유형도 지운다 — 사용자가 고른 유형은 남긴다
    private void clearResults() {
        requirements.clear();
        recommendations.clear();
        clarifications.clear();
        if (questionTypeBy == QuestionTypeBy.AI) {
            questionType = null;
            questionTypeBy = null;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Application getApplication() {
        return application;
    }

    /** 그 지원의 지원처 이름. */
    public String getOrganization() {
        return application.getOrganization();
    }

    public String getText() {
        return text;
    }

    public QuestionType getQuestionType() {
        return questionType;
    }

    public QuestionTypeBy getQuestionTypeBy() {
        return questionTypeBy;
    }

    public Integer getTargetLength() {
        return targetLength;
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

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }
}

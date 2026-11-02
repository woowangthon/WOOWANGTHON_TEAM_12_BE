package com.woowangthon.backend.job;

import com.woowangthon.backend.source.Source;
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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** 분석 작업 — 원본 하나를 AI 서버에 맡겨 활동 · 경험 · 근거를 뽑는 일. 실패하면 이유를 남긴다. */
@Entity
@Table(name = "analysis_job")
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String errorCode;

    private String errorMessage;

    @ElementCollection
    @CollectionTable(name = "analysis_job_activity", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "position")
    @Column(name = "activity_id", nullable = false)
    private List<Long> activityIds = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected AnalysisJob() {
    }

    public AnalysisJob(Long userId, Source source) {
        this.userId = userId;
        this.source = source;
        this.status = JobStatus.ANALYZING;
    }

    public void complete(List<Long> createdActivityIds) {
        status = JobStatus.COMPLETED;
        activityIds.clear();
        activityIds.addAll(createdActivityIds);
        errorCode = null;
        errorMessage = null;
    }

    public void fail(String code, String message) {
        status = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
    }

    /** 실패한 작업을 같은 원본으로 다시 돌린다. */
    public void restart() {
        status = JobStatus.ANALYZING;
        activityIds.clear();
        errorCode = null;
        errorMessage = null;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Source getSource() {
        return source;
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

    public List<Long> getActivityIds() {
        return activityIds;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

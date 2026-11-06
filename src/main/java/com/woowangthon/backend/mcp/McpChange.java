package com.woowangthon.backend.mcp;

import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.SourceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 외부 AI 가 바꾼 것 한 건(계약 「변경 기록」). 외부 쓰기 도구가 성공할 때마다 쓰기와 한 트랜잭션으로 남긴다. */
@Entity
@Table(name = "mcp_change")
public class McpChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private McpChangeType type;

    @Column(nullable = false)
    private String client;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private McpChangeStatus status = McpChangeStatus.APPLIED;

    private String reason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime decidedAt;

    private Long sourceId;

    private Long jobId;

    private Long experienceId;

    private Long activityId;

    private String activityName;

    private String beforeContent;

    private String afterContent;

    @Enumerated(EnumType.STRING)
    private SourceStatus beforeSourceStatus;

    protected McpChange() {
    }

    private McpChange(Long userId, McpChangeType type, String client) {
        this.userId = userId;
        this.type = type;
        this.client = client;
    }

    static McpChange addSource(long userId, String client, long sourceId, long jobId) {
        McpChange change = new McpChange(userId, McpChangeType.ADD_SOURCE, client);
        change.sourceId = sourceId;
        change.jobId = jobId;
        return change;
    }

    /** 고치기 전 경험에서 만든다 — before 값을 읽어 둔다. */
    static McpChange updateExperience(long userId, String client, Experience before, String afterContent, String reason) {
        McpChange change = new McpChange(userId, McpChangeType.UPDATE_EXPERIENCE, client);
        change.reason = reason;
        change.experienceId = before.getId();
        change.activityId = before.getActivity().getId();
        change.activityName = before.getActivity().getName();
        change.beforeContent = before.getContent();
        change.afterContent = afterContent;
        change.beforeSourceStatus = before.getSourceStatus();
        return change;
    }

    void undo() {
        status = McpChangeStatus.UNDONE;
        decidedAt = OffsetDateTime.now();
    }

    void confirm() {
        status = McpChangeStatus.CONFIRMED;
        decidedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public McpChangeType getType() {
        return type;
    }

    public String getClient() {
        return client;
    }

    public McpChangeStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Long getJobId() {
        return jobId;
    }

    public Long getExperienceId() {
        return experienceId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public String getActivityName() {
        return activityName;
    }

    public String getBeforeContent() {
        return beforeContent;
    }

    public String getAfterContent() {
        return afterContent;
    }

    public SourceStatus getBeforeSourceStatus() {
        return beforeSourceStatus;
    }
}

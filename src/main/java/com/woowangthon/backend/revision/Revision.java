package com.woowangthon.backend.revision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 수정 이력 한 건 = 한 번의 저장(계약 revision-api.md · ADR-013). 바뀌기 전 · 뒤 스냅샷을 통째로 JSON 글로 둔다 — 한 번 쓰면 고치지도 지우지도 않는다.
 * 대상에는 외래 키가 없다 — 대상을 지워도 이력은 남는다.
 */
@Entity
@Table(name = "revision")
public class Revision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RevisionTargetType targetType;

    @Column(nullable = false, updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RevisionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RevisionBy changedBy;

    @Column(updatable = false)
    private String client;

    @Column(updatable = false)
    private Long mcpChangeId;

    @Column(updatable = false)
    private Long restoredFromId;

    // DB(마이크로초)와 같은 정밀도로 둔다 — 목록 커서가 시각을 그대로 되돌려 비교한다
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // 목록 한 줄을 그리는 이름 — 기록한 때 값이다
    @Column(updatable = false)
    private Long targetActivityId;

    @Column(updatable = false)
    private String targetActivityName;

    @Column(updatable = false)
    private Long targetQuestionId;

    @Column(updatable = false)
    private String targetQuestionText;

    @Column(updatable = false)
    private Long targetApplicationId;

    @Column(updatable = false)
    private String targetOrganization;

    @Column(nullable = false, updatable = false)
    private String beforeSnapshot;

    @Column(nullable = false, updatable = false)
    private String afterSnapshot;

    protected Revision() {
    }

    Revision(long userId, RevisionTargetType targetType, long targetId, RevisionKind kind, RevisionOrigin origin,
            Long restoredFromId, TargetInfo target, String beforeSnapshot, String afterSnapshot) {
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.kind = kind;
        this.changedBy = origin.by();
        this.client = origin.client();
        this.mcpChangeId = origin.mcpChangeId();
        this.restoredFromId = restoredFromId;
        this.createdAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.targetActivityId = target.activityId();
        this.targetActivityName = target.activityName();
        this.targetQuestionId = target.questionId();
        this.targetQuestionText = target.questionText();
        this.targetApplicationId = target.applicationId();
        this.targetOrganization = target.organization();
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public RevisionTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public RevisionKind getKind() {
        return kind;
    }

    public RevisionBy getChangedBy() {
        return changedBy;
    }

    public String getClient() {
        return client;
    }

    public Long getMcpChangeId() {
        return mcpChangeId;
    }

    public Long getRestoredFromId() {
        return restoredFromId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public TargetInfo getTarget() {
        return new TargetInfo(targetActivityId, targetActivityName, targetQuestionId, targetQuestionText,
                targetApplicationId, targetOrganization);
    }

    public String getBeforeSnapshot() {
        return beforeSnapshot;
    }

    public String getAfterSnapshot() {
        return afterSnapshot;
    }
}

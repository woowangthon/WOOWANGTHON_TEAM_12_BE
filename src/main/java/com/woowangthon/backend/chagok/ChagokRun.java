package com.woowangthon.backend.chagok;

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

/** 차곡이 일 하나(V17). steps · result 는 Run 응답에 그대로 싣는 JSON 글이다. */
@Entity
@Table(name = "chagok_run")
public class ChagokRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String text;

    private Long questionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChagokStatus status = ChagokStatus.RUNNING;

    @Column(nullable = false)
    private String steps = "[]";

    private String result;

    private String errorCode;

    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime finishedAt;

    protected ChagokRun() {
    }

    ChagokRun(Long userId, String text, Long questionId) {
        this.userId = userId;
        this.text = text;
        this.questionId = questionId;
    }

    void changeSteps(String steps) {
        this.steps = steps;
    }

    void waitForConfirm() {
        this.status = ChagokStatus.WAITING_CONFIRM;
    }

    void done(String result) {
        this.status = ChagokStatus.DONE;
        this.result = result;
        this.finishedAt = OffsetDateTime.now();
    }

    void cancel() {
        this.status = ChagokStatus.CANCELED;
        this.finishedAt = OffsetDateTime.now();
    }

    void fail(String code, String message) {
        this.status = ChagokStatus.FAILED;
        this.errorCode = code;
        this.errorMessage = message;
        this.finishedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getText() {
        return text;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public ChagokStatus getStatus() {
        return status;
    }

    public String getSteps() {
        return steps;
    }

    public String getResult() {
        return result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

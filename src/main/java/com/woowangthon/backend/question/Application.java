package com.woowangthon.backend.question;

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
import org.hibernate.annotations.CreationTimestamp;

/** 지원 — 지원처 이름 · 종류 · 직무(모두 선택)와 문항 여러 개(SPEC R-03 용어, question-api.md 「Application」). 문항은 넣은 순서대로. */
@Entity
@Table(name = "application")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private String organization;

    @Enumerated(EnumType.STRING)
    private OrganizationType organizationType;

    @Enumerated(EnumType.STRING)
    private JobRole jobRole;

    @OneToMany(mappedBy = "application")
    @OrderBy("id asc")
    private List<Question> questions = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Application() {
    }

    public Application(Long userId, String organization) {
        this(userId, organization, null, null);
    }

    public Application(Long userId, String organization, OrganizationType organizationType, JobRole jobRole) {
        this.userId = userId;
        this.organization = organization;
        this.organizationType = organizationType;
        this.jobRole = jobRole;
    }

    /** 지원처 종류를 바꾼다 — null 이면 비운다(S-42). */
    public void changeOrganizationType(OrganizationType organizationType) {
        this.organizationType = organizationType;
    }

    /** 직무를 바꾼다 — null 이면 비운다(S-42). */
    public void changeJobRole(JobRole jobRole) {
        this.jobRole = jobRole;
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

    public OrganizationType getOrganizationType() {
        return organizationType;
    }

    public JobRole getJobRole() {
        return jobRole;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

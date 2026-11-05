package com.woowangthon.backend.question;

import com.woowangthon.backend.common.Times;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;

/** 지원 — 지원처 이름 · 종류 · 직무 · 공고 원문 링크(모두 선택) · 상태 · 낸 달과 문항 여러 개(SPEC R-03 용어, question-api.md 「Application」). 문항은 넣은 순서대로. */
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus applicationStatus = ApplicationStatus.WRITING;

    private String submittedMonth;

    private String postingUrl;

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
        this(userId, organization, organizationType, jobRole, null);
    }

    /** postingUrl — 공고 원문 링크(S-46). 넣을 때만 정하고 고치지 않는다. */
    public Application(Long userId, String organization, OrganizationType organizationType, JobRole jobRole,
            String postingUrl) {
        this.userId = userId;
        this.organization = organization;
        this.organizationType = organizationType;
        this.jobRole = jobRole;
        this.postingUrl = postingUrl;
    }

    /** 지원처 종류를 바꾼다 — null 이면 비운다(S-42). */
    public void changeOrganizationType(OrganizationType organizationType) {
        this.organizationType = organizationType;
    }

    /** 직무를 바꾼다 — null 이면 비운다(S-42). */
    public void changeJobRole(JobRole jobRole) {
        this.jobRole = jobRole;
    }

    /** 상태를 바꾼다(S-36) — 낸 달은 작성 중이면 없고, 작성 중을 벗어나면 그 달(서울)이 붙는다. 다른 상태끼리는 그대로. */
    public void changeStatus(ApplicationStatus status) {
        this.applicationStatus = status;
        if (status == ApplicationStatus.WRITING) {
            this.submittedMonth = null;
        } else if (this.submittedMonth == null) {
            this.submittedMonth = YearMonth.now(Times.SEOUL).toString();
        }
    }

    /** 낸 달을 고친다(S-37) — 예전에 낸 지원을 나중에 넣었을 때 그해 자리에 놓이게. */
    public void changeSubmittedMonth(String submittedMonth) {
        this.submittedMonth = submittedMonth;
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

    public ApplicationStatus getApplicationStatus() {
        return applicationStatus;
    }

    public String getSubmittedMonth() {
        return submittedMonth;
    }

    public String getPostingUrl() {
        return postingUrl;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

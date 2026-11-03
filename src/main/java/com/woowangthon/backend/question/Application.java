package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 지원 — 지원처 이름(선택) 하나와 문항 여러 개(SPEC R-03 용어, question-api.md 「Application」). 문항은 넣은 순서대로. */
@Entity
@Table(name = "application")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private String organization;

    @OneToMany(mappedBy = "application")
    @OrderBy("id asc")
    private List<Question> questions = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Application() {
    }

    public Application(Long userId, String organization) {
        this.userId = userId;
        this.organization = organization;
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

    public List<Question> getQuestions() {
        return questions;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

package com.woowangthon.backend.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 사용자 — 아이디 · 비밀번호로 로그인한다(BE#10). dailyAnalysisLimit 이 null 이면 설정값 한도. */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String loginId;

    private String passwordHash;

    private Integer dailyAnalysisLimit;

    // 소식을 어디까지 봤나(work-api.md) — 한 번도 안 봤으면 null
    private OffsetDateTime workSeenAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AppUser() {
    }

    public AppUser(String loginId, String passwordHash, Integer dailyAnalysisLimit) {
        this.name = loginId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.dailyAnalysisLimit = dailyAnalysisLimit;
    }

    public Long getId() {
        return id;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Integer getDailyAnalysisLimit() {
        return dailyAnalysisLimit;
    }

    public OffsetDateTime getWorkSeenAt() {
        return workSeenAt;
    }

    void reset(String passwordHash, Integer dailyAnalysisLimit) {
        this.passwordHash = passwordHash;
        this.dailyAnalysisLimit = dailyAnalysisLimit;
    }
}

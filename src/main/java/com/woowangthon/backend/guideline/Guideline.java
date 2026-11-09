package com.woowangthon.backend.guideline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** 작성 지침 한 줄 + 출처(guideline-api.md 「모양」). 사용자의 것이다 — 우리가 미리 넣지 않는다(SPEC S-49). */
@Entity
@Table(name = "guideline")
public class Guideline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String text;

    /** 선택 — 없으면 null(빈 문자열로 두지 않는다. BE#138). */
    @Column
    private String source;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Guideline() {
    }

    public Guideline(Long userId, String text, String source, boolean enabled) {
        this.userId = userId;
        this.text = text;
        this.source = source;
        this.enabled = enabled;
    }

    /** null 인 칸은 그대로 둔다 — 값 확인은 GuidelineService 가 먼저 한다. 출처만 null 이 「지움」이라 sourceGiven 으로 구분한다. */
    void edit(String newText, boolean sourceGiven, String newSource, Boolean newEnabled) {
        if (newText != null) {
            text = newText;
        }
        if (sourceGiven) {
            source = newSource;
        }
        if (newEnabled != null) {
            enabled = newEnabled;
        }
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getSource() {
        return source;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

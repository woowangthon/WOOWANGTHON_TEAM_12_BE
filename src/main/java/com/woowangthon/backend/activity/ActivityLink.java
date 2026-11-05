package com.woowangthon.backend.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 이어짐(S-35) — 「from 활동의 계기가 to 활동으로 이어졌나」 한 쌍. 새 행은 ActivityLinkRepository.insertSuggested 로만 만든다.
 * reason 은 AI 해석이라 고치지 않는다.
 */
@Entity
@Table(name = "activity_link")
public class ActivityLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_activity_id")
    private Activity fromActivity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_activity_id")
    private Activity toActivity;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkStatus status;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ActivityLink() {
    }

    public void decide(LinkStatus status) {
        this.status = status;
    }

    /** 활동 합치기 — 사라지는 활동을 가리키던 쪽을 남길 활동으로 옮긴다. */
    void replace(Activity gone, Activity keep) {
        if (fromActivity.getId().equals(gone.getId())) {
            fromActivity = keep;
        }
        if (toActivity.getId().equals(gone.getId())) {
            toActivity = keep;
        }
    }

    public Long getId() {
        return id;
    }

    public Activity getFromActivity() {
        return fromActivity;
    }

    public Activity getToActivity() {
        return toActivity;
    }

    public String getReason() {
        return reason;
    }

    public LinkStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

package com.woowangthon.backend.activity;

import com.woowangthon.backend.experience.Experience;
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
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.UpdateTimestamp;

/** 활동 — 프로젝트 · 동아리 · 수업 · 대회 같은 한 덩어리. 경험 여럿을 묶는다. */
@Entity
@Table(name = "activity")
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    private String periodStart;

    private String periodEnd;

    @Column(nullable = false)
    private boolean ongoing;

    // 같은 활동 제안(S-44) — 같아 보이는 기존 활동의 id. 분석 작업만 붙이고, 따로 두기 · 합치기 때 null
    private Long mergeSuggestionActivityId;

    @Formula("(select count(*) from experience e where e.activity_id = id)")
    private int experienceCount;

    @OneToMany(mappedBy = "activity")
    @OrderBy("position asc, id asc")
    private List<Experience> experiences = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Activity() {
    }

    public Activity(Long userId, String name, Period period) {
        this.userId = userId;
        this.name = name;
        changePeriod(period);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changePeriod(Period period) {
        this.periodStart = period.start();
        this.periodEnd = period.end();
        this.ongoing = period.ongoing();
    }

    /** 분석 작업이 새 활동에 붙인다(S-44). 그 밖에서는 부르지 않는다. */
    public void suggestMergeWith(Long activityId) {
        this.mergeSuggestionActivityId = activityId;
    }

    public void dismissMergeSuggestion() {
        this.mergeSuggestionActivityId = null;
    }

    public Long getMergeSuggestionActivityId() {
        return mergeSuggestionActivityId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Period getPeriod() {
        return new Period(periodStart, periodEnd, ongoing);
    }

    public int getExperienceCount() {
        return experienceCount;
    }

    public List<Experience> getExperiences() {
        return experiences;
    }
}

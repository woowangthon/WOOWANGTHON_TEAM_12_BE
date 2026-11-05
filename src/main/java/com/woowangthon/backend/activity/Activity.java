package com.woowangthon.backend.activity;

import com.woowangthon.backend.experience.Experience;
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

    // 그때의 나(S-34) — 계기 · 그때 든 생각 한 줄. 사용자만 쓴다. 경험이 아니라 추천 · 초안 근거로 쓰지 않는다
    private String note;

    // 분류(S-57) — 분석이 붙이면 AI, 사용자가 고르면 USER. 둘 다 null 이면 미분류. 정리용이라 매칭 · 초안 · MCP 에 싣지 않는다
    @Enumerated(EnumType.STRING)
    private ActivityCategory category;

    @Enumerated(EnumType.STRING)
    private CategoryBy categoryBy;

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

    public void changeNote(String note) {
        this.note = note;
    }

    /** 분석이 새 활동에 붙인다(S-57). null 이면 미분류로 둔다 — 억지로 고르지 않는다. */
    public void categorizeByAi(ActivityCategory category) {
        this.category = category;
        this.categoryBy = category == null ? null : CategoryBy.AI;
    }

    /** 사용자가 고르거나 비운다(S-57). 값이 실제로 바뀔 때만 USER — 같은 값이면 출처도 그대로. */
    public void chooseCategory(ActivityCategory category) {
        if (category == null) {
            this.category = null;
            this.categoryBy = null;
        } else if (category != this.category) {
            this.category = category;
            this.categoryBy = CategoryBy.USER;
        }
    }

    /** 합치기(S-57) — 남길 활동이 미분류일 때만 합쳐 오는 활동의 분류 · 출처를 받는다. */
    public void takeCategoryIfNone(Activity from) {
        if (this.category == null && from.category != null) {
            this.category = from.category;
            this.categoryBy = from.categoryBy;
        }
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

    public ActivityCategory getCategory() {
        return category;
    }

    public CategoryBy getCategoryBy() {
        return categoryBy;
    }

    public String getNote() {
        return note;
    }

    public int getExperienceCount() {
        return experienceCount;
    }

    public List<Experience> getExperiences() {
        return experiences;
    }
}

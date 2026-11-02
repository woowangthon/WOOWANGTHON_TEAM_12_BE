package com.woowangthon.backend.experience;

import com.woowangthon.backend.activity.Activity;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** 경험 — 활동 안에서 실제로 한 일 하나. 근거 여럿이 붙는다. */
@Entity
@Table(name = "experience")
public class Experience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @Column(nullable = false)
    private String content;

    // 역량 값 이름 배열(text[]) — 저장한 순서대로 돌려준다
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] competencies = new String[0];

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Actor actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceStatus sourceStatus;

    // 활동 안에서 보여 줄 순서 — AI 가 돌려준 순서, 옮겨 오거나 더한 경험은 뒤에
    @Column(nullable = false)
    private int position;

    @OneToMany(mappedBy = "experience")
    @OrderBy("id asc")
    private List<Citation> citations = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Experience() {
    }

    public Experience(Activity activity, String content, List<Competency> competencies, Actor actor,
            SourceStatus sourceStatus, int position) {
        this.activity = activity;
        this.content = content;
        this.competencies = toNames(competencies);
        this.actor = actor;
        this.sourceStatus = sourceStatus;
        this.position = position;
    }

    /**
     * 사용자가 고친 값을 넣는다(null 인 인자는 안 보낸 필드 — competencies 를 지우는 건 빈 목록). 실제로 바뀐 값이 하나라도 있으면
     * 출처 상태를 「내가 입력」으로 바꾼다. 근거는 건드리지 않는다(S-10).
     */
    public void editByUser(String newContent, List<Competency> newCompetencies, Actor newActor) {
        boolean changed = false;
        if (newContent != null && !newContent.equals(content)) {
            content = newContent;
            changed = true;
        }
        // 통째로 바꾼다 — 순서가 다르면 바뀐 것
        if (newCompetencies != null && !Arrays.equals(toNames(newCompetencies), competencies)) {
            competencies = toNames(newCompetencies);
            changed = true;
        }
        if (newActor != null && newActor != actor) {
            actor = newActor;
            changed = true;
        }
        if (changed) {
            sourceStatus = SourceStatus.USER_ENTERED;
        }
    }

    /** 다른 활동으로 옮긴다 — 묶음만 바뀌고 내용 · 근거 · 출처 상태는 그대로다(S-12). */
    public void moveTo(Activity target, int newPosition) {
        this.activity = target;
        this.position = newPosition;
    }

    public Long getId() {
        return id;
    }

    public Activity getActivity() {
        return activity;
    }

    public String getContent() {
        return content;
    }

    public List<Competency> getCompetencies() {
        return Arrays.stream(competencies).map(Competency::valueOf).toList();
    }

    private static String[] toNames(List<Competency> values) {
        return values.stream().map(Competency::name).toArray(String[]::new);
    }

    public Actor getActor() {
        return actor;
    }

    public SourceStatus getSourceStatus() {
        return sourceStatus;
    }

    public List<Citation> getCitations() {
        return citations;
    }
}

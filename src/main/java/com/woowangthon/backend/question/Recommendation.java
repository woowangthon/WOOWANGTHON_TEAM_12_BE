package com.woowangthon.backend.question;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 추천 — 문항에 맞는 경험 하나와 AI 해석(이유 · 강조점 · 부족한 점), 그리고 받치는 근거 · 보완. */
@Entity
@Table(name = "question_recommendation")
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id")
    private Question question;

    @Column(nullable = false)
    private int position;

    // 경험이 지워질 수 있어 외래 키 없이 ID 만 둔다 — 조회할 때 지워졌으면 이 추천을 뺀다
    @Column(nullable = false)
    private Long experienceId;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String emphasis;

    @ElementCollection
    @CollectionTable(name = "question_recommendation_evidence", joinColumns = @JoinColumn(name = "recommendation_id"))
    @OrderColumn(name = "position")
    private List<Evidence> evidence = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] gaps = new String[0];

    protected Recommendation() {
    }

    public Recommendation(Question question, int position, Long experienceId, String reason, String emphasis,
            List<Evidence> evidence, List<String> gaps) {
        this.question = question;
        this.position = position;
        this.experienceId = experienceId;
        this.reason = reason;
        this.emphasis = emphasis;
        this.evidence.addAll(evidence);
        this.gaps = gaps.toArray(String[]::new);
    }

    public Long getExperienceId() {
        return experienceId;
    }

    public String getReason() {
        return reason;
    }

    public String getEmphasis() {
        return emphasis;
    }

    public List<Evidence> getEvidence() {
        return evidence;
    }

    public List<String> getGaps() {
        return Arrays.asList(gaps);
    }
}

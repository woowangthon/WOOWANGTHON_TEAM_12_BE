package com.woowangthon.backend.question;

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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사용자가 확인한 공고 요건 줄(S-91). 지원의 소유자에게만 반환한다. */
@Entity
@Table(name = "application_posting_line")
public class ApplicationPostingLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostingSection section;

    @Column(nullable = false, length = 300)
    private String text;

    @Enumerated(EnumType.STRING)
    private Ksa ksa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReadFrom readFrom;

    // 공고 기준 경험 찾기(S-93)의 이 줄 판정 — 아직 없으면 null. 받치는 경험은 지워질 수 있어 ID 만(조회할 때 뺀다)
    @Enumerated(EnumType.STRING)
    private RequirementStatus matchStatus;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "bigint[]")
    private Long[] matchExperienceIds = new Long[0];

    protected ApplicationPostingLine() {
    }

    public ApplicationPostingLine(Application application, int position, PostingSection section,
            String text, Ksa ksa, ReadFrom readFrom) {
        this.application = application;
        this.position = position;
        this.section = section;
        this.text = text;
        this.ksa = ksa;
        this.readFrom = readFrom;
    }

    void judge(RequirementStatus status, List<Long> experienceIds) {
        this.matchStatus = status;
        this.matchExperienceIds = experienceIds.toArray(Long[]::new);
    }

    void clearMatch() {
        this.matchStatus = null;
        this.matchExperienceIds = new Long[0];
    }

    public Long getId() { return id; }
    public Application getApplication() { return application; }
    public RequirementStatus getMatchStatus() { return matchStatus; }
    public List<Long> getMatchExperienceIds() { return List.of(matchExperienceIds); }
    public PostingSection getSection() { return section; }
    public String getText() { return text; }
    public Ksa getKsa() { return ksa; }
    public ReadFrom getReadFrom() { return readFrom; }
}

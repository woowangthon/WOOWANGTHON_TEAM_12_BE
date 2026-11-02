package com.woowangthon.backend.experience;

import com.woowangthon.backend.source.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 근거 — 경험을 뒷받침하는 원본의 발췌문. 사용자가 경험을 고치거나 옮겨도 바뀌지 않는다. */
@Entity
@Table(name = "citation")
public class Citation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experience_id")
    private Experience experience;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private Source source;

    @Column(nullable = false)
    private String quote;

    // 발췌문을 원본에서 찾았나 — 대조하지 않았으면 null
    private Boolean matched;

    // 원본 텍스트 기준 1부터 세는 줄 번호(선택)
    private Integer startLine;

    private Integer endLine;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Citation() {
    }

    public Citation(Experience experience, Source source, String quote, Boolean matched,
            Integer startLine, Integer endLine) {
        this.experience = experience;
        this.source = source;
        this.quote = quote;
        this.matched = matched;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public Long getId() {
        return id;
    }

    public Source getSource() {
        return source;
    }

    public String getQuote() {
        return quote;
    }

    public Boolean getMatched() {
        return matched;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }
}

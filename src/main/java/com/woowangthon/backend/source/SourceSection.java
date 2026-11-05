package com.woowangthon.backend.source;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** 원본의 쪽 · 슬라이드 하나가 text 의 몇째 줄부터 몇째 줄까지인지(1부터, 양끝 포함) — S-39. */
@Embeddable
public class SourceSection {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SectionKind kind;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false)
    private int startLine;

    @Column(nullable = false)
    private int endLine;

    protected SourceSection() {
    }

    public SourceSection(SectionKind kind, int number, int startLine, int endLine) {
        this.kind = kind;
        this.number = number;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public SectionKind getKind() {
        return kind;
    }

    public int getNumber() {
        return number;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    boolean contains(int line) {
        return startLine <= line && line <= endLine;
    }
}

package com.woowangthon.backend.source;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;

/** 원본 — 사용자가 넣은 자료 하나(붙여넣은 텍스트 또는 .md · .txt · .pdf · .pptx 에서 뽑은 글자). */
@Entity
@Table(name = "source")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceKind kind;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String text;

    // 받은 글자의 해시(S-69) — 같은 자료 알아보기에만 쓴다. 마이그레이션 전 원본은 null 일 수 있다
    @Column(length = 64)
    private String contentHash;

    // .pdf · .pptx 만 — 쪽 · 슬라이드별 줄 구간(S-39). 그 밖엔 비었다
    @ElementCollection
    @CollectionTable(name = "source_section", joinColumns = @JoinColumn(name = "source_id"))
    @OrderBy("startLine")
    private List<SourceSection> sections = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Source() {
    }

    public Source(Long userId, SourceKind kind, String name, String text) {
        this(userId, kind, name, text, List.of());
    }

    public Source(Long userId, SourceKind kind, String name, String text, List<SourceSection> sections) {
        this.userId = userId;
        this.kind = kind;
        this.name = name;
        this.text = text;
        this.contentHash = SourceHash.of(text);
        this.sections = new ArrayList<>(sections);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getText() {
        return text;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<SourceSection> getSections() {
        return List.copyOf(sections);
    }

    /** 줄 번호(1부터)가 든 쪽 · 슬라이드. 구간이 없거나 어느 구간에도 들지 않으면(쪽 사이 빈 줄 등) 빈 값. */
    public Optional<SourceSection> sectionOf(int line) {
        return sections.stream().filter(s -> s.contains(line)).findFirst();
    }
}

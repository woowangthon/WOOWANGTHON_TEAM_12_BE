package com.woowangthon.backend.question;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** 초안의 문장 하나와 받치는 근거 · 보완 · 경험(만들었을 때 그대로). */
@Entity
@Table(name = "draft_sentence")
public class DraftSentence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "draft_id")
    private Draft draft;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftSentenceKind kind;

    @Column(nullable = false)
    private String text;

    @ElementCollection
    @CollectionTable(name = "draft_sentence_evidence", joinColumns = @JoinColumn(name = "sentence_id"))
    @OrderColumn(name = "position")
    private List<Evidence> evidence = new ArrayList<>();

    protected DraftSentence() {
    }

    public DraftSentence(Draft draft, int position, DraftSentenceKind kind, String text, List<Evidence> evidence) {
        this.draft = draft;
        this.position = position;
        this.kind = kind;
        this.text = text;
        this.evidence.addAll(evidence);
    }

    public DraftSentenceKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public List<Evidence> getEvidence() {
        return evidence;
    }
}

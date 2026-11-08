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

    // ELABORATED 만 — 왜 확인이 필요한지 한 줄(S-78). 나머지 · 옛 문장은 null
    private String note;

    // 사용자가 카드에서 「맞아요」를 눌렀나(S-79) — 바꾸는 곳은 DraftSentenceRepository.confirmOwned 뿐
    @Column(nullable = false)
    private boolean confirmed;

    @ElementCollection
    @CollectionTable(name = "draft_sentence_evidence", joinColumns = @JoinColumn(name = "sentence_id"))
    @OrderColumn(name = "position")
    private List<Evidence> evidence = new ArrayList<>();

    protected DraftSentence() {
    }

    public DraftSentence(Draft draft, int position, DraftSentenceKind kind, String text, String note,
            List<Evidence> evidence) {
        this.draft = draft;
        this.position = position;
        this.kind = kind;
        this.text = text;
        this.note = note;
        this.evidence.addAll(evidence);
    }

    public Long getId() {
        return id;
    }

    public DraftSentenceKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public String getNote() {
        return note;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<Evidence> getEvidence() {
        return evidence;
    }
}

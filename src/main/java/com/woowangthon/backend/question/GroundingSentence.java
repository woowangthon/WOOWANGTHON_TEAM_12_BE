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

/** 근거 점검의 문장 하나 — 판정과 받치는 근거 · 보완 · 경험(점검했을 때 그대로). */
@Entity
@Table(name = "grounding_sentence")
public class GroundingSentence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id")
    private GroundingCheck check;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroundingStatus status;

    private String note;

    @ElementCollection
    @CollectionTable(name = "grounding_sentence_evidence", joinColumns = @JoinColumn(name = "sentence_id"))
    @OrderColumn(name = "position")
    private List<Evidence> evidence = new ArrayList<>();

    protected GroundingSentence() {
    }

    public GroundingSentence(GroundingCheck check, int position, String text, GroundingStatus status,
            List<Evidence> evidence, String note) {
        this.check = check;
        this.position = position;
        this.text = text;
        this.status = status;
        this.evidence.addAll(evidence);
        this.note = note;
    }

    public String getText() {
        return text;
    }

    public GroundingStatus getStatus() {
        return status;
    }

    public List<Evidence> getEvidence() {
        return evidence;
    }

    public String getNote() {
        return note;
    }
}

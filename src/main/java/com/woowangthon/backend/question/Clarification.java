package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 되묻기 — 추천한 경험에서 문항에 필요한데 자료에 없는 사실을 묻는 질문. 답은 그 경험의 보완이 된다(BE#23). */
@Entity
@Table(name = "clarification")
public class Clarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id")
    private Question question;

    @Column(nullable = false)
    private int position;

    // 경험이 지워질 수 있어 외래 키 없이 ID 만 둔다
    @Column(nullable = false)
    private Long experienceId;

    @Column(name = "question", nullable = false)
    private String text;

    protected Clarification() {
    }

    public Clarification(Question question, int position, Long experienceId, String text) {
        this.question = question;
        this.position = position;
        this.experienceId = experienceId;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Question getQuestion() {
        return question;
    }

    public Long getExperienceId() {
        return experienceId;
    }

    public String getText() {
        return text;
    }
}

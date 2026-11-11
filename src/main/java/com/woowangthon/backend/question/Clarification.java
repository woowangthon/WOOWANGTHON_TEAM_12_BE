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

/**
 * 되묻기 — 추천한 경험에서 문항에 필요한데 자료에 없는 사실을 묻는 질문. 답은 그 경험의 보완이 된다(BE#23). 초안의 빠진 사실에서 만든 것은
 * draftId 가 있다(BE#71) — 추천의 되묻기 목록엔 섞이지 않고, 초안을 다시 만들면 지운다. 공고 기준 경험 찾기(S-93)의 되묻기는 문항 대신
 * 공고 요건 줄에 붙는다(BE#174).
 */
@Entity
@Table(name = "clarification")
public class Clarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 문항 되묻기면 question, 공고 기준 경험 찾기(S-93)의 되묻기면 postingLine — 둘 중 하나만(DB check)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id")
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posting_line_id")
    private ApplicationPostingLine postingLine;

    @Column(nullable = false)
    private int position;

    // 경험이 지워질 수 있어 외래 키 없이 ID 만 둔다
    @Column(nullable = false)
    private Long experienceId;

    @Column(name = "question", nullable = false)
    private String text;

    // 외래 키는 SQL 에만 — 초안 행을 다시 쓸 때 DraftService 가 지운다
    private Long draftId;

    protected Clarification() {
    }

    public Clarification(Question question, int position, Long experienceId, String text) {
        this.question = question;
        this.position = position;
        this.experienceId = experienceId;
        this.text = text;
    }

    /** 초안의 빠진 사실에서 만든 되묻기. */
    public static Clarification forDraft(Question question, Long draftId, int position, Long experienceId, String text) {
        Clarification c = new Clarification(question, position, experienceId, text);
        c.draftId = draftId;
        return c;
    }

    /** 공고 요건 줄의 되묻기(S-93) — 답하기는 문항 되묻기와 같은 경로다. position 은 지원 안의 순서. */
    public static Clarification forPostingLine(ApplicationPostingLine line, int position, Long experienceId, String text) {
        Clarification c = new Clarification(null, position, experienceId, text);
        c.postingLine = line;
        return c;
    }

    public ApplicationPostingLine getPostingLine() {
        return postingLine;
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

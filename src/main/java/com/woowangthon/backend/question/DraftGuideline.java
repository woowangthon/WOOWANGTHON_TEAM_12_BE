package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 초안에 실은 지침 하나(question-api.md 「답변 초안」 guidelines). 글과 출처는 만들 때의 것을 옮겨 둔다 — 지침을 고치거나 지워도 그대로다.
 * applied · note 는 AI 해석이고, 응답이 오기 전엔 null 이다.
 */
@Embeddable
public class DraftGuideline {

    @Column(name = "guideline_id", nullable = false)
    private Long guidelineId;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "source")
    private String source;

    @Column(name = "applied")
    private Boolean applied;

    @Column(name = "note")
    private String note;

    protected DraftGuideline() {
    }

    public DraftGuideline(Long guidelineId, String text, String source) {
        this(guidelineId, text, source, null, null);
    }

    private DraftGuideline(Long guidelineId, String text, String source, Boolean applied, String note) {
        this.guidelineId = guidelineId;
        this.text = text;
        this.source = source;
        this.applied = applied;
        this.note = note;
    }

    /** AI 가 돌려준 반영 여부를 붙인 것. */
    public DraftGuideline judged(boolean newApplied, String newNote) {
        return new DraftGuideline(guidelineId, text, source, newApplied, newNote);
    }

    public Long getGuidelineId() {
        return guidelineId;
    }

    public String getText() {
        return text;
    }

    public String getSource() {
        return source;
    }

    public Boolean getApplied() {
        return applied;
    }

    public String getNote() {
        return note;
    }
}

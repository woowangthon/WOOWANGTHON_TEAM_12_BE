package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 받칠 게 없어 쓰지 않은 것 하나(question-api.md 「답변 초안」 omitted). 경험에 붙은 것은 질문과 되묻기 ID 가 있고, 붙지 않은 빈칸은 text 만
 * 있다. 문장 속 빈칸이면 자리 표시와 그 문장 순번이 함께 있다(S-78).
 */
@Embeddable
public class DraftOmitted {

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "question")
    private String question;

    @Column(name = "experience_id")
    private Long experienceId;

    @Column(name = "clarification_id")
    private Long clarificationId;

    @Column(name = "placeholder")
    private String placeholder;

    @Column(name = "sentence_index")
    private Integer sentenceIndex;

    protected DraftOmitted() {
    }

    public DraftOmitted(String text, String question, Long experienceId, Long clarificationId, String placeholder,
            Integer sentenceIndex) {
        this.text = text;
        this.question = question;
        this.experienceId = experienceId;
        this.clarificationId = clarificationId;
        this.placeholder = placeholder;
        this.sentenceIndex = sentenceIndex;
    }

    public String getText() {
        return text;
    }

    public String getQuestion() {
        return question;
    }

    public Long getExperienceId() {
        return experienceId;
    }

    public Long getClarificationId() {
        return clarificationId;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public Integer getSentenceIndex() {
        return sentenceIndex;
    }
}

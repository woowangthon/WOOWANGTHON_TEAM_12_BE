package com.woowangthon.backend.question;

import com.woowangthon.backend.experience.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 확정 답의 새 경험 후보 하나(question-api.md 「확정 답변」). content · actor · activityId 는 AI 해석 — 저장할 때 사용자가 고친 값을 쓴다.
 * activityId · experienceId 는 그 활동 · 경험이 지워지면 DB 가 null 로 돌린다.
 */
@Entity
@Table(name = "new_fact_candidate")
public class NewFactCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id")
    private Answer answer;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String sentence;

    @Column(nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Actor actor;

    private Long activityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NewFactStatus status;

    private Long experienceId;

    protected NewFactCandidate() {
    }

    public NewFactCandidate(Answer answer, int position, String sentence, String content, Actor actor, Long activityId) {
        this.answer = answer;
        this.position = position;
        this.sentence = sentence;
        this.content = content;
        this.actor = actor;
        this.activityId = activityId;
        this.status = NewFactStatus.PENDING;
    }

    void save(Long newExperienceId) {
        status = NewFactStatus.SAVED;
        experienceId = newExperienceId;
    }

    void dismiss() {
        status = NewFactStatus.DISMISSED;
    }

    public Long getId() {
        return id;
    }

    public Answer getAnswer() {
        return answer;
    }

    public String getSentence() {
        return sentence;
    }

    public String getContent() {
        return content;
    }

    public Actor getActor() {
        return actor;
    }

    public Long getActivityId() {
        return activityId;
    }

    public NewFactStatus getStatus() {
        return status;
    }

    public Long getExperienceId() {
        return experienceId;
    }
}

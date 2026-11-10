package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 답변 초안 — 문항마다 최신 하나(question-api.md 「답변 초안」). 다시 만들면 같은 행을 처음부터 다시 쓴다(createdAt 도).
 */
@Entity
@Table(name = "draft")
public class Draft {

    public static final int MAX_TITLE = 40;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long questionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String errorCode;

    private String errorMessage;

    @ElementCollection
    @CollectionTable(name = "draft_experience", joinColumns = @JoinColumn(name = "draft_id"))
    @OrderColumn(name = "position")
    @Column(name = "experience_id", nullable = false)
    private List<Long> experienceIds = new ArrayList<>();

    @OneToMany(mappedBy = "draft", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<DraftSentence> sentences = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "draft_omitted", joinColumns = @JoinColumn(name = "draft_id"))
    @OrderColumn(name = "position")
    private List<DraftOmitted> omitted = new ArrayList<>();

    // AI 가 제안한 첫 줄 소제목(S-86) — 문장이 0개 · 만드는 중 · 실패 · 옛 초안은 null. 사용자가 고친 것은 저장하지 않는다(답칸 글이 원본)
    @Column(length = MAX_TITLE)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftFrame frame = DraftFrame.NONE;

    // 같은 활동의 다른 경험도 뒷받침 재료로 쓰나(S-75) — 요청 값 그대로, 다시 만들 때 FE 가 골라 둔다
    @Column(nullable = false)
    private boolean useContext = true;

    // 초안을 시작할 때 켜 둔 지침 — 실패하면 비운다(계약: ANALYZING · FAILED 면 응답은 [])
    @ElementCollection
    @CollectionTable(name = "draft_guideline", joinColumns = @JoinColumn(name = "draft_id"))
    @OrderColumn(name = "position")
    private List<DraftGuideline> guidelines = new ArrayList<>();

    // 옛 문자열 형식 omitted 로 만든 초안(ETC#139) — 응답에선 없는 것으로 보고 다시 만들면 풀린다
    @Column(nullable = false)
    private boolean legacyOmitted;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Draft() {
    }

    public Draft(Long questionId) {
        this.questionId = questionId;
    }

    /** 고른 경험 · 틀 · 보조 재료 쓰기 · 지금 켜 둔 지침으로 새로 만든다 — 예전 결과 · 실패 이유를 지운다. */
    public void start(List<Long> chosen, DraftFrame newFrame, boolean withContext, List<DraftGuideline> enabled) {
        sentences.clear();
        omitted.clear();
        experienceIds.clear();
        experienceIds.addAll(chosen);
        frame = newFrame;
        useContext = withContext;
        guidelines.clear();
        guidelines.addAll(enabled);
        title = null;
        legacyOmitted = false;
        status = JobStatus.ANALYZING;
        errorCode = null;
        errorMessage = null;
        createdAt = OffsetDateTime.now();
    }

    /** 문장들은 이 초안을 가리키게 만든 것이어야 한다. */
    public void complete(String newTitle, List<DraftSentence> newSentences, List<DraftOmitted> newOmitted,
            List<DraftGuideline> judged) {
        title = newTitle;
        sentences.clear();
        sentences.addAll(newSentences);
        omitted.clear();
        omitted.addAll(newOmitted);
        guidelines.clear();
        guidelines.addAll(judged);
        status = JobStatus.COMPLETED;
        errorCode = null;
        errorMessage = null;
    }

    public void fail(String code, String message) {
        title = null;
        sentences.clear();
        omitted.clear();
        guidelines.clear();
        status = JobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<Long> getExperienceIds() {
        return experienceIds;
    }

    public String getTitle() {
        return title;
    }

    public List<DraftSentence> getSentences() {
        return sentences;
    }

    public List<DraftOmitted> getOmitted() {
        return omitted;
    }

    public DraftFrame getFrame() {
        return frame;
    }

    public boolean isUseContext() {
        return useContext;
    }

    public List<DraftGuideline> getGuidelines() {
        return guidelines;
    }

    public boolean isLegacyOmitted() {
        return legacyOmitted;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

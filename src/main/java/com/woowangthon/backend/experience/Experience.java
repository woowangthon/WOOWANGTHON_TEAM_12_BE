package com.woowangthon.backend.experience;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.question.Question;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** 경험 — 활동 안에서 실제로 한 일 하나. 근거 여럿 · 보완 여럿이 붙는다. */
@Entity
@Table(name = "experience")
public class Experience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @Column(nullable = false)
    private String content;

    // 역량 값 이름 배열(text[]) — 저장한 순서대로 돌려준다
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] competencies = new String[0];

    // 지금 competencies 를 누가 붙였나 — 역량이 실제로 바뀔 때만 USER 가 된다
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompetenciesBy competenciesBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Actor actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceStatus sourceStatus;

    // 활동 안에서 보여 줄 순서 — AI 가 돌려준 순서, 옮겨 오거나 더한 경험은 뒤에
    @Column(nullable = false)
    private int position;

    @OneToMany(mappedBy = "experience")
    @OrderBy("id asc")
    private List<Citation> citations = new ArrayList<>();

    // 되묻기에 답해 쌓인 보완 — 만든 순서(BE#23)
    @OneToMany(mappedBy = "experience")
    @OrderBy("id asc")
    private List<Supplement> supplements = new ArrayList<>();

    // 확정 답의 새 경험 후보를 저장해 생긴 경험만 그 문항 · 지원(S-48 · origin). 고치거나 옮기거나 합쳐도, 문항을 지워도 그대로다(S-89)
    @Column(name = "origin_question_id", updatable = false)
    private Long originQuestionId;

    @Column(name = "origin_application_id", updatable = false)
    private Long originApplicationId;

    // 응답 때 지원처 이름 · 문항 글을 지금 값으로 읽는다 — 지운 문항이면 null(외래 키 없음)
    @ManyToOne(fetch = FetchType.LAZY)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "origin_question_id", insertable = false, updatable = false)
    private Question originQuestion;

    // 외부 AI 가 고쳐 사용자가 아직 정하지 않았을 때만 찬다(S-53 · 계약 mcpChange). 응답마다 변경 기록을 읽지 않으려고 여기 둔다
    private Long mcpChangeId;

    private String mcpClient;

    // 공개 프로필에 넣었나(S-65) — 웹에서 사용자만 바꾼다. 내용을 고친 게 아니라 출처 상태를 건드리지 않는다
    @Column(nullable = false)
    private boolean shared;

    // 다시 쓰려고 저장했나(S-71) — 웹에서 사용자만 바꾼다. 내용을 고친 게 아니라 출처 상태를 건드리지 않는다
    @Column(nullable = false)
    private boolean saved;

    // STAR 정리 상태 · 칸(S-95 · ETC#327). 칸은 READY 가 된 적이 있을 때만 넷이 있다 — 다시 정리하는 동안 · 실패해도 앞 칸은 남는다
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StarStatus starStatus = StarStatus.NONE;

    @ElementCollection
    @CollectionTable(name = "experience_star_slot", joinColumns = @JoinColumn(name = "experience_id"))
    private List<StarSlot> starSlots = new ArrayList<>();

    // 수행 주체가 UNKNOWN 에서 처음 정해진 시각(S-77) — 그 뒤 다시 만든 초안은 세지 않는다. 한 번 채우면 덮지 않는다
    private OffsetDateTime actorDecidedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Experience() {
    }

    public Experience(Activity activity, String content, List<Competency> competencies, CompetenciesBy competenciesBy,
            Actor actor, SourceStatus sourceStatus, int position) {
        this.activity = activity;
        this.content = content;
        this.competencies = toNames(competencies);
        this.competenciesBy = competenciesBy;
        this.actor = actor;
        this.sourceStatus = sourceStatus;
        this.position = position;
    }

    /**
     * 사용자가 고친 값을 넣는다(null 인 인자는 안 보낸 필드 — competencies 를 지우는 건 빈 목록). 본문이 실제로 바뀌었을 때만
     * 출처 상태를 「내가 입력」으로 바꾸고 외부 AI 확인 대기를 푼다 — 주체 · 역량을 정한 것은 본문을 확인한 게 아니다(S-10 · ETC#249).
     * 역량이 실제로 바뀌었을 때만 역량 출처를 USER 로 바꾼다. 근거는 건드리지 않는다.
     */
    public void editByUser(String newContent, List<Competency> newCompetencies, Actor newActor) {
        boolean contentChanged = false;
        if (newContent != null && !newContent.equals(content)) {
            content = newContent;
            contentChanged = true;
        }
        // 통째로 바꾼다 — 순서가 다르면 바뀐 것
        if (newCompetencies != null && !Arrays.equals(toNames(newCompetencies), competencies)) {
            competencies = toNames(newCompetencies);
            competenciesBy = CompetenciesBy.USER;
        }
        if (newActor != null && newActor != actor) {
            changeActor(newActor);
        }
        if (contentChanged) {
            sourceStatus = SourceStatus.USER_ENTERED;
            mcpChangeId = null;
            mcpClient = null;
        }
    }

    /**
     * 활동 단위로 수행 주체를 정한다(S-76) — 아직 UNKNOWN 일 때만 바꾸고, 바꿨으면 true. 이미 ME · TEAM 으로 정한 값은 덮지 않는다.
     * editByUser 와 달리 출처 상태 · 외부 AI 확인 표시는 그대로다 — 본문을 고친 게 아니다.
     */
    public boolean decideActorIfUnknown(Actor decided) {
        if (actor != Actor.UNKNOWN) {
            return false;
        }
        changeActor(decided);
        return true;
    }

    // 처음 정하거나 바꾼 때만 시각을 남긴다(ME↔TEAM 포함, S-77) — 바꿨다 되돌려 무료 다시 만들기를 거듭 받지 않게.
    // 외부 AI 되돌리기의 「사용자가 손댄 경험」에도 쓴다
    private void changeActor(Actor newActor) {
        // 나 말고는 T(내 몫)를 원문 근거로 채우지 않는다 — 다시 정리하지 않고 그 칸만 비운다(계약 「다른 동작과의 관계」 · ADR-019)
        if (actor == Actor.ME && newActor != Actor.ME) {
            for (StarSlot slot : List.copyOf(starSlots)) {
                if (slot.getSlot() == StarSlotName.T && slot.getSourceType() == StarSourceType.CITATION) {
                    replaceStarSlot(slot.cleared());
                }
            }
        }
        if (newActor != actor && newActor != Actor.UNKNOWN && actorDecidedAt == null) {
            actorDecidedAt = OffsetDateTime.now();
        }
        actor = newActor;
    }

    /**
     * 외부 AI 가 본문을 고친다 — 본문만 바뀌고 「확인 필요」로 낮춘다. 사용자가 정하기 전엔 초안 · 근거 점검의 근거가 되지 않는다(S-53 · ADR-010).
     * 역량 · 역량 출처 · 수행 주체 · 근거 · 보완은 그대로다.
     */
    public void rewriteByExternalAi(String newContent, long changeId, String client) {
        content = newContent;
        sourceStatus = SourceStatus.NEEDS_REVIEW;
        mcpChangeId = changeId;
        mcpClient = client;
    }

    /**
     * 외부 AI 고치기를 되돌린다 — 본문 · 출처 상태만 이전 값으로(S-54). 그 앞에 아직 정하지 않은 외부 AI 고치기가 있으면
     * 그 변경이 다시 이 경험의 mcpChange 가 된다(없으면 둘 다 null).
     */
    public void undoExternalAiRewrite(String beforeContent, SourceStatus beforeSourceStatus, Long pendingChangeId,
            String pendingClient) {
        content = beforeContent;
        sourceStatus = beforeSourceStatus;
        mcpChangeId = pendingChangeId;
        mcpClient = pendingClient;
    }

    /**
     * 수정 이력에서 되돌린다(S-80) — 스냅샷 다섯 필드를 통째로 그때 값으로. 출처 상태도 그때 값이라 USER_ENTERED 로 바꾸지 않는다.
     * 정하지 않은 외부 AI 변경은 사용자가 손댄 것이 되어 mcpChange 가 비고, 주체를 정한 시각은 웹 고치기와 같이 처음 한 번만 남는다.
     * 근거 · 보완 · 활동 소속은 그대로다.
     */
    public void restoreSnapshot(String newContent, Actor newActor, List<Competency> newCompetencies,
            CompetenciesBy newCompetenciesBy, SourceStatus newSourceStatus) {
        content = newContent;
        competencies = toNames(newCompetencies);
        competenciesBy = newCompetenciesBy;
        sourceStatus = newSourceStatus;
        if (newActor != actor) {
            changeActor(newActor);
        }
        mcpChangeId = null;
        mcpClient = null;
    }

    /** 맞아요 — 외부 AI 가 고친 본문을 사용자가 자기 말로 받는다(S-53). 역량 출처는 그대로다. */
    public void confirmExternalAiRewrite() {
        sourceStatus = SourceStatus.USER_ENTERED;
        mcpChangeId = null;
        mcpClient = null;
    }

    /** STAR 정리를 시작한다 — 앞 칸은 끝날 때까지 남겨 두고(실패하면 그대로 보인다) 응답엔 싣지 않는다. */
    public void startStar() {
        starStatus = StarStatus.ANALYZING;
    }

    /** STAR 정리 결과(S · T · A · R 넷)로 칸을 통째로 바꾼다. */
    public void completeStar(List<StarSlot> slots) {
        starSlots.clear();
        starSlots.addAll(slots);
        starStatus = StarStatus.READY;
    }

    /** STAR 정리 실패 — 앞 칸은 그대로. */
    public void failStar() {
        starStatus = StarStatus.FAILED;
    }

    /** 칸이 있나 — NONE · ANALYZING 이거나 정리된 적이 없으면 없다(계약 STAR_NOT_READY). */
    public boolean hasStarSlots() {
        return starStatus != StarStatus.NONE && starStatus != StarStatus.ANALYZING && !starSlots.isEmpty();
    }

    /** 칸 하나를 갈아 끼운다. */
    public void replaceStarSlot(StarSlot slot) {
        for (int i = 0; i < starSlots.size(); i++) {
            if (starSlots.get(i).getSlot() == slot.getSlot()) {
                starSlots.set(i, slot);
                return;
            }
        }
        starSlots.add(slot);
    }

    /** 칸이 가리키던 보완이 지워졌다 — 그 칸은 빈칸(질문은 남는다). 다시 정리하지 않는다(ADR-019). */
    public void onSupplementDeleted(long supplementId) {
        for (StarSlot slot : List.copyOf(starSlots)) {
            if (slot.points(StarSourceType.SUPPLEMENT, supplementId)) {
                replaceStarSlot(slot.cleared());
            }
        }
    }

    /**
     * 칸이 가리키는 보완의 답이 바뀌었다 — 답 전부를 가리키던 칸은 새 답을 따르고, 일부를 가리키던 칸은 그 글이 새 답에 남아 있으면
     * 그대로, 사라졌으면 빈칸이다(칸 글은 늘 출처 글에 적혀 있다 — S-95). 다시 정리하지 않는다(ADR-019). except 칸은 부르는 쪽이 채운다.
     */
    public void onSupplementChanged(long supplementId, String oldAnswer, String newAnswer, StarSlotName except) {
        Supplement supplement = supplements.stream().filter(s -> s.getId() == supplementId).findFirst().orElse(null);
        for (StarSlot slot : List.copyOf(starSlots)) {
            if (slot.getSlot() == except || !slot.points(StarSourceType.SUPPLEMENT, supplementId)) {
                continue;
            }
            if (StarText.normalize(slot.getText()).equals(StarText.normalize(oldAnswer)) && supplement != null) {
                replaceStarSlot(slot.pointingTo(supplement, newAnswer));
                continue;
            }
            if (!StarText.contains(newAnswer, slot.getText())) {
                replaceStarSlot(slot.cleared());
            }
        }
    }

    public StarStatus getStarStatus() {
        return starStatus;
    }

    public List<StarSlot> getStarSlots() {
        return starSlots;
    }

    /** 확정 답에서 왔다고 표시한다 — 만들 때 한 번만. */
    public void markFromAnswer(Question question) {
        this.originQuestionId = question.getId();
        this.originApplicationId = question.getApplication().getId();
        this.originQuestion = question;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }

    public boolean isSaved() {
        return saved;
    }

    public void setSaved(boolean saved) {
        this.saved = saved;
    }

    public Long getMcpChangeId() {
        return mcpChangeId;
    }

    public String getMcpClient() {
        return mcpClient;
    }

    public Long getOriginQuestionId() {
        return originQuestionId;
    }

    public Long getOriginApplicationId() {
        return originApplicationId;
    }

    /** 지금 있는 문항 — 지웠으면 null. */
    public Question getOriginQuestion() {
        return originQuestion;
    }

    /** 다른 활동으로 옮긴다 — 묶음만 바뀌고 내용 · 근거 · 출처 상태는 그대로다(S-12). */
    public void moveTo(Activity target, int newPosition) {
        this.activity = target;
        this.position = newPosition;
    }

    public Long getId() {
        return id;
    }

    public Activity getActivity() {
        return activity;
    }

    public String getContent() {
        return content;
    }

    public List<Competency> getCompetencies() {
        return Arrays.stream(competencies).map(Competency::valueOf).toList();
    }

    public CompetenciesBy getCompetenciesBy() {
        return competenciesBy;
    }

    private static String[] toNames(List<Competency> values) {
        return values.stream().map(Competency::name).toArray(String[]::new);
    }

    public OffsetDateTime getActorDecidedAt() {
        return actorDecidedAt;
    }

    public Actor getActor() {
        return actor;
    }

    public SourceStatus getSourceStatus() {
        return sourceStatus;
    }

    public List<Citation> getCitations() {
        return citations;
    }

    public List<Supplement> getSupplements() {
        return supplements;
    }

    /** AI 에 근거로 보내도 되는 보완 — 외부 AI 를 거쳐 아직 확인 전인 답은 뺀다(S-56). */
    public List<Supplement> getConfirmedSupplements() {
        return supplements.stream().filter(Supplement::isConfirmed).toList();
    }
}

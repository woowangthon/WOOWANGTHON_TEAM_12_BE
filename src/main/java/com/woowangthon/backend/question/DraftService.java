package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.DraftRequest;
import com.woowangthon.backend.ai.DraftResponse;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import com.woowangthon.backend.guideline.GuidelineRepository;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고른 경험으로 답변 초안 시작 → AI 초안 → 결과 저장 (question-api.md 「답변 초안」, ai-api.md 「POST /draft」). 문항마다 최신 하나.
 * AI 호출 자체는 트랜잭션 밖(DraftRunner)에서 한다.
 */
@Service
public class DraftService {

    private static final int MAX_EXPERIENCES = 3;

    // ai-api.md 「응답 규칙」
    private static final int MAX_SENTENCES = 15;
    private static final int MAX_OMITTED = 5;
    private static final int MAX_OPINIONS = 3;
    private static final int MAX_GUIDELINE_NOTE = 80;

    private final QuestionRepository questions;
    private final DraftRepository drafts;
    private final ExperienceRepository experiences;
    private final SentenceEvidence evidence;
    private final UsageLimiter limiter;
    private final ClarificationRepository clarifications;
    private final SupplementRepository supplements;
    private final GuidelineRepository guidelines;

    public DraftService(QuestionRepository questions, DraftRepository drafts, ExperienceRepository experiences,
            SentenceEvidence evidence, UsageLimiter limiter, ClarificationRepository clarifications,
            SupplementRepository supplements, GuidelineRepository guidelines) {
        this.questions = questions;
        this.drafts = drafts;
        this.experiences = experiences;
        this.evidence = evidence;
        this.limiter = limiter;
        this.clarifications = clarifications;
        this.supplements = supplements;
        this.guidelines = guidelines;
    }

    /** frame 을 빼면 NONE. 값 목록 밖이면 본문을 읽지 못해 400 이다. */
    public record StartRequest(List<Long> experienceIds, DraftFrame frame) {
    }

    public record Sentence(DraftSentenceKind kind, String text, List<SentenceEvidence.Ref> evidence) {
    }

    /** 경험에 붙지 않은 빈칸은 text 만 있고 나머지는 null. supplementId 는 지금 그 되묻기에 달린 보완. */
    public record Omitted(String text, String question, Long experienceId, Long clarificationId, Long supplementId) {
    }

    /** 글 · 출처는 초안을 만들 때의 것이다. id 가 지금 없는 지침일 수 있다. */
    public record AppliedGuideline(Long id, String text, String source, Boolean applied, String note) {
    }

    public record DraftView(Long questionId, JobStatus status, ErrorResponse.Error error, List<Long> experienceIds,
            List<Sentence> sentences, List<Omitted> omitted, DraftFrame frame, List<AppliedGuideline> guidelines,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * 초안을 시작한다 — 그 문항의 예전 초안을 이것으로 바꾼다. 진행 중(409) · 한도(429)면 예전 것을 그대로 둔다. 문항 행을 잠가 두 번 눌러도
     * 하나만 시작한다. 예전 초안의 되묻기는 지운다 — 그 ID 로는 더 답할 수 없고, 이미 쌓인 보완은 남는다. 답 저장도 같은 문항 행을 잠그므로
     * 교체와 겹쳐 지운 되묻기로 보완이 생기지 않는다(ClarificationService).
     */
    @Transactional
    public DraftView start(long questionId, StartRequest request) {
        List<Long> chosen = checkShape(request == null ? null : request.experienceIds());
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다"));
        if (question.getStatus() != JobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "QUESTION_NOT_COMPLETED",
                    "문항 " + questionId + " 의 추천이 끝나지 않았습니다(" + question.getStatus() + ")");
        }
        checkRecommended(question, chosen);
        Draft draft = drafts.findByQuestionId(questionId).orElse(null);
        if (draft != null && draft.getStatus() == JobStatus.ANALYZING) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_IN_PROGRESS",
                    "문항 " + questionId + " 의 초안을 아직 만드는 중입니다");
        }
        limiter.consume(question.getUserId());
        if (draft == null) {
            draft = new Draft(questionId);
        } else {
            clarifications.deleteByDraftId(draft.getId());
        }
        DraftFrame frame = request.frame() == null ? DraftFrame.NONE : request.frame();
        // 시작하는 이 순간 켜 둔 지침을 옮겨 둔다 — AI 를 기다리는 새 지침을 바꿔도 이 초안에 실리는 건 그대로다
        draft.start(chosen, frame, guidelines.findByUserIdAndEnabledTrueOrderByIdAsc(question.getUserId()).stream()
                .map(g -> new DraftGuideline(g.getId(), g.getText(), g.getSource())).toList());
        drafts.saveAndFlush(draft);
        return view(draft, question.getUserId());
    }

    /** 문항 응답에 싣는 초안 — 아직 만들지 않았거나 옛 문자열 형식이면 null. 문항을 읽는 트랜잭션 안에서 부른다. */
    public DraftView find(Question question) {
        return drafts.findByQuestionId(question.getId()).filter(d -> !d.isLegacyOmitted())
                .map(d -> view(d, question.getUserId())).orElse(null);
    }

    /**
     * AI 에 보낼 것 — 문항과 고른 경험만, 고른 순서대로, 지금 상태로. 저장된 매칭 결과(요구사항 · 고른 경험의 이유 · 강조점 · 부족한 점)와
     * 지원의 직무를 그대로 싣는다(ETC#141) — LLM 을 더 부르지 않는다. 직무가 없으면 칸을 싣지 않는다.
     * 틀 · 지침은 시작할 때 초안에 옮겨 둔 것이고, NONE · 0개면 칸을 싣지 않는다(S-49 · S-50).
     */
    @Transactional(readOnly = true)
    public DraftRequest request(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        Draft draft = drafts.findByQuestionId(questionId).orElseThrow();
        Map<Long, Experience> found = experiences.findAllOwned(draft.getExperienceIds(), question.getUserId()).stream()
                .collect(Collectors.toMap(Experience::getId, Function.identity()));
        Map<Long, Recommendation> recommended = question.getRecommendations().stream()
                .collect(Collectors.toMap(Recommendation::getExperienceId, Function.identity(), (a, b) -> a));
        List<DraftRequest.ExperiencePart> parts = draft.getExperienceIds().stream()
                .map(found::get).filter(Objects::nonNull)
                .map(e -> experiencePart(SentenceEvidence.matchedPart(e), recommended.get(e.getId()))).toList();
        List<DraftRequest.RequirementPart> requirements = question.getRequirements().stream()
                .map(r -> new DraftRequest.RequirementPart(r.getText(), r.getStatus())).toList();
        List<DraftRequest.GuidelinePart> loaded = draft.getGuidelines().stream()
                .map(g -> new DraftRequest.GuidelinePart(g.getGuidelineId(), g.getText())).toList();
        return new DraftRequest(
                new DraftRequest.QuestionPart(question.getOrganization(), question.getText(),
                        question.getApplication().getJobRole(), draft.getFrame() == DraftFrame.NONE ? null : draft.getFrame(),
                        requirements.isEmpty() ? null : requirements),
                parts, loaded.isEmpty() ? null : loaded);
    }

    private static DraftRequest.ExperiencePart experiencePart(MatchRequest.ExperiencePart base, Recommendation r) {
        return r == null ? new DraftRequest.ExperiencePart(base, null, null, null)
                : new DraftRequest.ExperiencePart(base, r.getReason(), r.getEmphasis(), List.copyOf(r.getGaps()));
    }

    /**
     * AI 응답을 문장으로 저장한다. 계약(ai-api.md 「응답 규칙」)을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고 AiCallException(AI_FAILED)을
     * 던진다 — 고쳐서 쓰지 않는다. ID 는 보낸 요청(sent)에 있었는지로 확인한다. 문장 속 숫자 대조는 AI 서버 코드가 한다.
     */
    @Transactional
    public void complete(long questionId, DraftRequest sent, DraftResponse result) {
        Draft draft = drafts.lockByQuestionId(questionId).orElseThrow();
        if (draft.getStatus() != JobStatus.ANALYZING) {
            return; // 그새 재시작 정리로 실패가 됐다 — 덮지 않는다
        }
        SentenceEvidence.Sent ids = new SentenceEvidence.Sent(sent.baseExperiences());

        List<DraftResponse.SentenceResult> found = orEmpty(result.sentences());
        if (found.size() > MAX_SENTENCES) {
            throw AiCallException.invalidResponse("sentences 는 " + MAX_SENTENCES + "개까지입니다");
        }
        if (found.isEmpty() && ids.hasBacking()) {
            throw AiCallException.invalidResponse("받칠 것이 있는데 sentences 가 비었습니다");
        }
        List<DraftSentence> sentences = new ArrayList<>();
        int opinions = 0;
        for (int position = 0; position < found.size(); position++) {
            DraftResponse.SentenceResult s = found.get(position);
            if (s.text() == null || s.text().isBlank() || s.text().contains("\n") || s.text().contains("\r")) {
                throw AiCallException.invalidResponse("sentences[" + position + "].text 는 줄바꿈 없는 한 문장이어야 합니다");
            }
            DraftSentenceKind kind = kind(position, s.kind());
            List<Evidence> backing = ids.checked(s.evidence());
            // 사실은 받치는 것이 1개 이상, 생각(「AI 제안 — 확인 필요」)은 근거를 붙이지 않는다
            if (kind == DraftSentenceKind.FACT && backing.isEmpty()) {
                throw AiCallException.invalidResponse("sentences[" + position + "].evidence 가 비었습니다");
            }
            if (kind == DraftSentenceKind.OPINION && !backing.isEmpty()) {
                throw AiCallException.invalidResponse("sentences[" + position + "] 는 OPINION 이라 evidence 가 비어야 합니다");
            }
            if (kind == DraftSentenceKind.OPINION && ++opinions > MAX_OPINIONS) {
                throw AiCallException.invalidResponse("OPINION 문장은 " + MAX_OPINIONS + "개까지입니다");
            }
            sentences.add(new DraftSentence(draft, position, kind, s.text(), backing));
        }

        List<DraftGuideline> judged = judged(draft, orEmpty(result.guidelines()));
        List<DraftOmitted> omitted = omitted(questionId, draft, sent, orEmpty(result.omitted()));
        draft.complete(sentences, omitted, judged);
    }

    // ai-api.md 응답 규칙 guidelines — 실은 지침마다 하나씩, 같은 순서로. 빠진 지침을 「반영함」으로 채우지 않는다
    private static List<DraftGuideline> judged(Draft draft, List<DraftResponse.GuidelineResult> found) {
        List<DraftGuideline> loaded = draft.getGuidelines();
        if (found.size() != loaded.size()) {
            throw AiCallException.invalidResponse("guidelines 는 보낸 지침 " + loaded.size() + "개마다 하나씩이어야 합니다");
        }
        List<DraftGuideline> judged = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            DraftResponse.GuidelineResult r = found.get(i);
            DraftGuideline g = loaded.get(i);
            if (r == null || !g.getGuidelineId().equals(r.id())) {
                throw AiCallException.invalidResponse("guidelines[" + i + "].id 가 보낸 순서의 지침이 아닙니다");
            }
            if (r.applied() == null) {
                throw AiCallException.invalidResponse("guidelines[" + i + "].applied 가 없습니다");
            }
            if (r.applied() ? r.note() != null : !isNote(r.note())) {
                throw AiCallException.invalidResponse("guidelines[" + i + "].note 는 applied: true 면 null, false 면 줄바꿈 없는 1 ~ "
                        + MAX_GUIDELINE_NOTE + "자여야 합니다");
            }
            judged.add(g.judged(r.applied(), r.note()));
        }
        return judged;
    }

    private static boolean isNote(String note) {
        return note != null && !note.isBlank() && note.length() <= MAX_GUIDELINE_NOTE && !note.contains("\n")
                && !note.contains("\r");
    }

    // 새 응답에는 kind 가 필수다 — 빠졌다고 FACT 로 채우지 않는다(채우는 건 옛 저장 초안뿐, V21)
    private static DraftSentenceKind kind(int position, String kind) {
        try {
            return DraftSentenceKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw AiCallException.invalidResponse("sentences[" + position + "].kind 는 FACT · OPINION 중 하나여야 합니다: " + kind);
        }
    }

    // 모양을 다 확인한 뒤, 경험에 붙은 빈칸마다 되묻기를 만든다 — 추천 되묻기와 같은 답 저장 경로를 쓴다(ai-api.md 「응답 규칙」 omitted)
    private List<DraftOmitted> omitted(long questionId, Draft draft, DraftRequest sent,
            List<DraftResponse.OmittedResult> found) {
        if (found.size() > MAX_OMITTED) {
            throw AiCallException.invalidResponse("omitted 는 " + MAX_OMITTED + "개까지입니다");
        }
        Set<Long> sentIds = sent.experiences().stream().map(DraftRequest.ExperiencePart::id).collect(Collectors.toSet());
        for (int i = 0; i < found.size(); i++) {
            DraftResponse.OmittedResult o = found.get(i);
            if (o == null || o.text() == null || o.text().isBlank()) {
                throw AiCallException.invalidResponse("omitted[" + i + "].text 가 비었습니다");
            }
            if ((o.experienceId() == null) != (o.question() == null)) {
                throw AiCallException.invalidResponse("omitted[" + i + "] 의 question 과 experienceId 는 함께 null 이어야 합니다");
            }
            if (o.question() != null && o.question().isBlank()) {
                throw AiCallException.invalidResponse("omitted[" + i + "].question 이 비었습니다");
            }
            if (o.experienceId() != null && !sentIds.contains(o.experienceId())) {
                throw AiCallException.invalidResponse("omitted[" + i + "].experienceId 가 보낸 경험이 아닙니다: " + o.experienceId());
            }
        }
        Question question = questions.getReferenceById(questionId);
        List<DraftOmitted> omitted = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            DraftResponse.OmittedResult o = found.get(i);
            Long clarificationId = o.experienceId() == null ? null
                    : clarifications.save(Clarification.forDraft(question, draft.getId(), i, o.experienceId(), o.question()))
                            .getId();
            omitted.add(new DraftOmitted(o.text(), o.question(), o.experienceId(), clarificationId));
        }
        return omitted;
    }

    @Transactional
    public void fail(long questionId, String code, String message) {
        drafts.lockByQuestionId(questionId).orElseThrow().fail(code, message);
    }

    /** 문장의 근거 · 고른 경험을 지금 상태로 — 그새 지워진 것은 뺀다. 문장은 만들었을 때 그대로다. */
    private DraftView view(Draft d, long userId) {
        Map<String, SentenceEvidence.Ref> current = evidence.current(userId);
        List<Long> chosen = d.getExperienceIds().stream()
                .filter(id -> current.containsKey(SentenceEvidence.key(EvidenceType.EXPERIENCE, id))).toList();
        List<Sentence> sentences = d.getSentences().stream()
                .map(s -> new Sentence(s.getKind(), s.getText(), SentenceEvidence.resolve(s.getEvidence(), current))).toList();
        ErrorResponse.Error error = d.getErrorCode() == null ? null
                : new ErrorResponse.Error(d.getErrorCode(), d.getErrorMessage());
        List<AppliedGuideline> applied = d.getStatus() != JobStatus.COMPLETED ? List.of()
                : d.getGuidelines().stream().map(g -> new AppliedGuideline(g.getGuidelineId(), g.getText(), g.getSource(),
                        g.getApplied(), g.getNote())).toList();
        return new DraftView(d.getQuestionId(), d.getStatus(), error, chosen, sentences, omitted(d, current), d.getFrame(),
                applied, Times.seoul(d.getCreatedAt()), d.getUpdatedAt() == null ? null : Times.seoul(d.getUpdatedAt()));
    }

    // 경험이 그새 지워진 항목은 뺀다. 답한 보완은 지금 연결로 찾는다 — 보완을 지우면 null 로 돌아간다
    private List<Omitted> omitted(Draft d, Map<String, SentenceEvidence.Ref> current) {
        List<Long> asked = d.getOmitted().stream().map(DraftOmitted::getClarificationId).filter(Objects::nonNull).toList();
        Map<Long, Long> answered = asked.isEmpty() ? Map.of()
                : supplements.findByClarificationIdIn(asked).stream()
                        .collect(Collectors.toMap(Supplement::getClarificationId, Supplement::getId, (a, b) -> a));
        return d.getOmitted().stream()
                .filter(o -> o.getExperienceId() == null
                        || current.containsKey(SentenceEvidence.key(EvidenceType.EXPERIENCE, o.getExperienceId())))
                .map(o -> new Omitted(o.getText(), o.getQuestion(), o.getExperienceId(), o.getClarificationId(),
                        o.getClarificationId() == null ? null : answered.get(o.getClarificationId())))
                .toList();
    }

    private static List<Long> checkShape(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_EXPERIENCES) {
            throw ApiException.invalidRequest("experienceIds 는 1 ~ " + MAX_EXPERIENCES + "개여야 합니다");
        }
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.invalidRequest("experienceIds 에 빈 값이나 중복이 있습니다");
        }
        return ids;
    }

    // 그 문항 추천에 있고 아직 지워지지 않은 경험만 고를 수 있다
    private void checkRecommended(Question question, List<Long> chosen) {
        Set<Long> recommended = question.getRecommendations().stream().map(Recommendation::getExperienceId)
                .collect(Collectors.toSet());
        Set<Long> existing = experiences.findAllOwned(chosen, question.getUserId()).stream().map(Experience::getId)
                .collect(Collectors.toSet());
        for (Long id : chosen) {
            if (!recommended.contains(id) || !existing.contains(id)) {
                throw ApiException.invalidRequest("경험 " + id + " 은(는) 이 문항의 추천에 없습니다");
            }
        }
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}

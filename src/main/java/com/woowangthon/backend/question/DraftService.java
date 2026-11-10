package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.DraftRequest;
import com.woowangthon.backend.ai.DraftResponse;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import com.woowangthon.backend.guideline.GuidelineRepository;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    // question-api.md 「POST /api/questions/{questionId}/draft」 보조 재료(S-75) — 둘째 · 셋째 활동은 각 2개까지
    private static final int MAX_CONTEXT = 12;
    private static final int OTHER_ACTIVITY_SHARE = 2;

    // ai-api.md 「응답 규칙」
    private static final int MAX_SENTENCES = 15;
    private static final int MAX_OMITTED = 5;
    private static final int MAX_OPINIONS = 3;
    private static final int MAX_GUIDELINE_NOTE = 80;
    private static final int MAX_SENTENCE_NOTE = 60;
    private static final int MAX_PLACEHOLDER = 12;

    // 문장 속 빈칸 자리 표시 「[…]」(S-78)
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\[\\]]*\\]");

    private final QuestionRepository questions;
    private final DraftRepository drafts;
    private final ExperienceRepository experiences;
    private final SentenceEvidence evidence;
    private final UsageLimiter limiter;
    private final ClarificationRepository clarifications;
    private final SupplementRepository supplements;
    private final GuidelineRepository guidelines;
    private final DraftSentenceRepository sentences;

    public DraftService(QuestionRepository questions, DraftRepository drafts, ExperienceRepository experiences,
            SentenceEvidence evidence, UsageLimiter limiter, ClarificationRepository clarifications,
            SupplementRepository supplements, GuidelineRepository guidelines, DraftSentenceRepository sentences) {
        this.questions = questions;
        this.drafts = drafts;
        this.experiences = experiences;
        this.evidence = evidence;
        this.limiter = limiter;
        this.clarifications = clarifications;
        this.supplements = supplements;
        this.guidelines = guidelines;
        this.sentences = sentences;
    }

    /**
     * frame 을 빼면 NONE. 값 목록 밖이면 본문을 읽지 못해 400 이다. useContext 를 빼면 true — Object 로 받아 불리언이 아니면 400 이다(Jackson 은
     * "true" · 1 도 불리언으로 읽는다).
     */
    public record StartRequest(List<Long> experienceIds, DraftFrame frame, Object useContext) {
    }

    /** note 는 ELABORATED 만 있다. confirmed · removed 는 카드에서 「맞아요」 · 「빼기」를 눌렀나(S-79). */
    public record Sentence(Long id, DraftSentenceKind kind, String text, List<SentenceEvidence.Ref> evidence, String note,
            boolean confirmed, boolean removed) {
    }

    /**
     * 경험에 붙지 않은 빈칸은 text 만 있고 question · experienceId · clarificationId · supplementId · activityId · activityName ·
     * experienceContent · supplement 는 null. supplementId 와 supplement 는 지금 그 되묻기에 달린 보완(안 답했거나 지웠으면 null).
     * activityId · activityName · experienceContent 는 그 경험의 지금 값 — 추천에 없는 보조 재료 경험(S-75)도 FE 가 이 값으로 그린다.
     * 문장 속 빈칸이면 placeholder · sentenceIndex 가 함께 있다.
     */
    public record Omitted(String text, String question, Long experienceId, Long clarificationId, Long supplementId,
            String placeholder, Integer sentenceIndex, Long activityId, String activityName, String experienceContent,
            ExperienceResponse.SupplementResponse supplement) {
    }

    /** 글 · 출처는 초안을 만들 때의 것이다. id 가 지금 없는 지침일 수 있다. */
    public record AppliedGuideline(Long id, String text, String source, Boolean applied, String note) {
    }

    public record DraftView(Long questionId, JobStatus status, ErrorResponse.Error error, List<Long> experienceIds,
            List<Sentence> sentences, List<Omitted> omitted, DraftFrame frame, boolean useContext, boolean freeRegenerate,
            List<AppliedGuideline> guidelines, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * 초안을 시작한다 — 그 문항의 예전 초안을 이것으로 바꾼다. 진행 중(409) · 한도(429)면 예전 것을 그대로 둔다. 문항 행을 잠가 두 번 눌러도
     * 하나만 시작한다. 예전 초안의 되묻기는 지운다 — 그 ID 로는 더 답할 수 없고, 이미 쌓인 보완은 남는다. 답 저장도 같은 문항 행을 잠그므로
     * 교체와 겹쳐 지운 되묻기로 보완이 생기지 않는다(ClarificationService). 예전 초안의 빠진 사실에 하나라도 답했거나, 예전 초안을 시작한 뒤
     * 그 재료 경험의 주체를 정했으면(S-76) 분석 횟수를 세지 않는다(S-77).
     */
    @Transactional
    public DraftView start(long questionId, StartRequest request) {
        List<Long> chosen = checkShape(request == null ? null : request.experienceIds());
        boolean useContext = useContext(request.useContext());
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
        if (!freeRegenerate(draft, question.getUserId())) {
            limiter.consume(question.getUserId());
        }
        if (draft == null) {
            draft = new Draft(questionId);
        } else {
            clarifications.deleteByDraftId(draft.getId());
        }
        DraftFrame frame = request.frame() == null ? DraftFrame.NONE : request.frame();
        // 시작하는 이 순간 켜 둔 지침을 옮겨 둔다 — AI 를 기다리는 새 지침을 바꿔도 이 초안에 실리는 건 그대로다
        draft.start(chosen, frame, useContext, guidelines.findByUserIdAndEnabledTrueOrderByIdAsc(question.getUserId()).stream()
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
     * AI 에 보낼 것 — 문항과 고른 경험, 고른 순서대로, 지금 상태로. 켜 두었으면 같은 활동의 다른 경험도 보조 재료로 싣는다({@link #context}).
     * 저장된 매칭 결과(요구사항 · 고른 경험의 이유 · 강조점 · 부족한 점)와 지원의 직무를 그대로 싣는다(ETC#141) — LLM 을 더 부르지 않는다. 직무가 없으면 칸을 싣지 않는다.
     * 틀 · 지침은 시작할 때 초안에 옮겨 둔 것이고, NONE · 0개면 칸을 싣지 않는다(S-49 · S-50). 목표 글자 수는 지금 문항 값이다(S-77).
     */
    @Transactional(readOnly = true)
    public DraftRequest request(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        Draft draft = drafts.findByQuestionId(questionId).orElseThrow();
        Map<Long, Experience> found = experiences.findAllOwned(draft.getExperienceIds(), question.getUserId()).stream()
                .collect(Collectors.toMap(Experience::getId, Function.identity()));
        Map<Long, Recommendation> recommended = question.getRecommendations().stream()
                .collect(Collectors.toMap(Recommendation::getExperienceId, Function.identity(), (a, b) -> a));
        List<Experience> chosen = draft.getExperienceIds().stream().map(found::get).filter(Objects::nonNull).toList();
        List<DraftRequest.ExperiencePart> parts = chosen.stream()
                .map(e -> experiencePart(SentenceEvidence.matchedPart(e), recommended.get(e.getId()))).toList();
        List<MatchRequest.ExperiencePart> context = draft.isUseContext()
                ? context(chosen, question.getUserId()).stream().map(SentenceEvidence::matchedPart).toList()
                : null;
        List<DraftRequest.RequirementPart> requirements = question.getRequirements().stream()
                .map(r -> new DraftRequest.RequirementPart(r.getText(), r.getStatus())).toList();
        List<DraftRequest.GuidelinePart> loaded = draft.getGuidelines().stream()
                .map(g -> new DraftRequest.GuidelinePart(g.getGuidelineId(), g.getText())).toList();
        return new DraftRequest(
                new DraftRequest.QuestionPart(question.getOrganization(), question.getText(),
                        question.getApplication().getJobRole(), draft.getFrame() == DraftFrame.NONE ? null : draft.getFrame(),
                        requirements.isEmpty() ? null : requirements, question.getTargetLength()),
                parts, context, loaded.isEmpty() ? null : loaded);
    }

    /**
     * 보조 재료(S-75) — 고른 경험의 활동에서 고른 경험을 뺀 나머지, 최대 12개. 첫째 고른 경험의 활동이 먼저다: 둘째 · 셋째의 활동은 각 2개까지
     * 받고 나머지 몫은 첫째 활동이 받는다. 첫째 활동에 모자라면 남는 몫을 다음 활동으로 넘긴다. 같은 활동을 둘 이상 골랐으면 앞선 쪽으로 한 번만 센다.
     * 활동 안에서는 최근에 생긴 경험부터, 싣는 순서는 활동 순서대로.
     */
    private List<Experience> context(List<Experience> chosen, long userId) {
        List<Long> activityIds = chosen.stream().map(e -> e.getActivity().getId()).distinct().toList();
        if (activityIds.isEmpty()) {
            return List.of();
        }
        Set<Long> chosenIds = chosen.stream().map(Experience::getId).collect(Collectors.toSet());
        Map<Long, List<Experience>> byActivity = new LinkedHashMap<>();
        activityIds.forEach(id -> byActivity.put(id, new ArrayList<>()));
        for (Experience e : experiences.findRecentInActivities(activityIds, userId)) {
            if (!chosenIds.contains(e.getId())) {
                byActivity.get(e.getActivity().getId()).add(e);
            }
        }
        List<List<Experience>> candidates = new ArrayList<>(byActivity.values());
        int[] taken = new int[candidates.size()];
        int left = MAX_CONTEXT;
        for (int i = 1; i < candidates.size(); i++) {
            taken[i] = Math.min(OTHER_ACTIVITY_SHARE, candidates.get(i).size());
            left -= taken[i];
        }
        // 첫째 활동이 나머지 몫을 받고, 그래도 남으면 다음 활동이 차례로 더 받는다
        for (int i = 0; i < candidates.size() && left > 0; i++) {
            int more = Math.min(left, candidates.get(i).size() - taken[i]);
            taken[i] += more;
            left -= more;
        }
        List<Experience> context = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            context.addAll(candidates.get(i).subList(0, taken[i]));
        }
        return context;
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
        // 근거는 보조 재료 경험의 것도 된다(S-75). 문장이 0개인 건 고른 경험에 받칠 것이 없을 때만이다
        SentenceEvidence.Sent ids = new SentenceEvidence.Sent(sent.allExperiences());

        List<DraftResponse.SentenceResult> found = orEmpty(result.sentences());
        if (found.size() > MAX_SENTENCES) {
            throw AiCallException.invalidResponse("sentences 는 " + MAX_SENTENCES + "개까지입니다");
        }
        if (found.isEmpty() && new SentenceEvidence.Sent(sent.baseExperiences()).hasBacking()) {
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
            // 사실 · AI 보탬은 받치는 것이 1개 이상, 생각(「AI 제안 — 확인 필요」)은 근거를 붙이지 않는다
            if (kind != DraftSentenceKind.OPINION && backing.isEmpty()) {
                throw AiCallException.invalidResponse("sentences[" + position + "].evidence 가 비었습니다");
            }
            if (kind == DraftSentenceKind.OPINION && !backing.isEmpty()) {
                throw AiCallException.invalidResponse("sentences[" + position + "] 는 OPINION 이라 evidence 가 비어야 합니다");
            }
            if (kind == DraftSentenceKind.OPINION && ++opinions > MAX_OPINIONS) {
                throw AiCallException.invalidResponse("OPINION 문장은 " + MAX_OPINIONS + "개까지입니다");
            }
            // AI 보탬만 왜 확인이 필요한지 한 줄을 단다(S-78)
            if (kind == DraftSentenceKind.ELABORATED ? !isNote(s.note(), MAX_SENTENCE_NOTE) : s.note() != null) {
                throw AiCallException.invalidResponse("sentences[" + position + "].note 는 ELABORATED 면 줄바꿈 없는 1 ~ "
                        + MAX_SENTENCE_NOTE + "자, 나머지는 null 이어야 합니다");
            }
            sentences.add(new DraftSentence(draft, position, kind, s.text(), s.note(), backing));
        }

        List<DraftGuideline> judged = judged(draft, orEmpty(result.guidelines()));
        List<DraftOmitted> omitted = omitted(questionId, draft, sent, found, orEmpty(result.omitted()));
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
            if (r.applied() ? r.note() != null : !isNote(r.note(), MAX_GUIDELINE_NOTE)) {
                throw AiCallException.invalidResponse("guidelines[" + i + "].note 는 applied: true 면 null, false 면 줄바꿈 없는 1 ~ "
                        + MAX_GUIDELINE_NOTE + "자여야 합니다");
            }
            judged.add(g.judged(r.applied(), r.note()));
        }
        return judged;
    }

    private static boolean isNote(String note, int max) {
        return note != null && !note.isBlank() && note.length() <= max && !note.contains("\n") && !note.contains("\r");
    }

    // 새 응답에는 kind 가 필수다 — 빠졌다고 FACT 로 채우지 않는다(채우는 건 옛 저장 초안뿐, V21)
    private static DraftSentenceKind kind(int position, String kind) {
        try {
            return DraftSentenceKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw AiCallException.invalidResponse(
                    "sentences[" + position + "].kind 는 FACT · OPINION · ELABORATED 중 하나여야 합니다: " + kind);
        }
    }

    /**
     * 모양을 다 확인한 뒤, 경험에 붙은 빈칸마다 되묻기를 만든다 — 추천 되묻기와 같은 답 저장 경로를 쓴다(ai-api.md 「응답 규칙」 omitted). 보조 재료
     * 경험에 붙은 것도 되묻는다(S-75). 문장 속 「[…]」는 모두 빈칸 하나와 짝이어야 한다(S-78).
     */
    private List<DraftOmitted> omitted(long questionId, Draft draft, DraftRequest sent,
            List<DraftResponse.SentenceResult> sentences, List<DraftResponse.OmittedResult> found) {
        if (found.size() > MAX_OMITTED) {
            throw AiCallException.invalidResponse("omitted 는 " + MAX_OMITTED + "개까지입니다");
        }
        Set<Long> sentIds = sent.allExperiences().stream().map(MatchRequest.ExperiencePart::id)
                .collect(Collectors.toSet());
        Set<String> paired = new HashSet<>();
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
            if ((o.placeholder() == null) != (o.sentenceIndex() == null)) {
                throw AiCallException.invalidResponse("omitted[" + i + "] 의 placeholder 와 sentenceIndex 는 함께 null 이어야 합니다");
            }
            if (o.placeholder() != null) {
                checkPlaceholder(i, o, sentences);
                if (!paired.add(o.sentenceIndex() + ":" + o.placeholder())) {
                    throw AiCallException.invalidResponse("omitted[" + i + "] 와 같은 빈칸이 이미 있습니다: " + o.placeholder());
                }
            }
        }
        for (int position = 0; position < sentences.size(); position++) {
            Matcher m = PLACEHOLDER.matcher(sentences.get(position).text());
            while (m.find()) {
                if (!paired.contains(position + ":" + m.group())) {
                    throw AiCallException.invalidResponse(
                            "sentences[" + position + "] 의 빈칸 " + m.group() + " 에 맞는 omitted 가 없습니다");
                }
            }
        }
        Question question = questions.getReferenceById(questionId);
        List<DraftOmitted> omitted = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            DraftResponse.OmittedResult o = found.get(i);
            Long clarificationId = o.experienceId() == null ? null
                    : clarifications.save(Clarification.forDraft(question, draft.getId(), i, o.experienceId(), o.question()))
                            .getId();
            omitted.add(new DraftOmitted(o.text(), o.question(), o.experienceId(), clarificationId, o.placeholder(),
                    o.sentenceIndex()));
        }
        return omitted;
    }

    // 「[…]」 꼴(안이 비지 않음) 12자 이하, 가리키는 문장에 글자 그대로 한 번
    private static void checkPlaceholder(int i, DraftResponse.OmittedResult o,
            List<DraftResponse.SentenceResult> sentences) {
        String placeholder = o.placeholder();
        if (placeholder.length() > MAX_PLACEHOLDER || !PLACEHOLDER.matcher(placeholder).matches()
                || placeholder.substring(1, placeholder.length() - 1).isBlank()) {
            throw AiCallException.invalidResponse(
                    "omitted[" + i + "].placeholder 는 [ ] 로 감싼 " + MAX_PLACEHOLDER + "자 이하여야 합니다: " + placeholder);
        }
        int index = o.sentenceIndex();
        if (index < 0 || index >= sentences.size()) {
            throw AiCallException.invalidResponse("omitted[" + i + "].sentenceIndex 가 문장 범위 밖입니다: " + index);
        }
        String text = sentences.get(index).text();
        int at = text.indexOf(placeholder);
        if (at < 0 || text.indexOf(placeholder, at + 1) >= 0) {
            throw AiCallException.invalidResponse(
                    "omitted[" + i + "].placeholder 가 sentences[" + index + "] 에 한 번 있어야 합니다: " + placeholder);
        }
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
                .map(s -> new Sentence(s.getId(), s.getKind(), s.getText(),
                        SentenceEvidence.resolve(s.getEvidence(), current), s.getNote(), s.isConfirmed(), s.isRemoved()))
                .toList();
        ErrorResponse.Error error = d.getErrorCode() == null ? null
                : new ErrorResponse.Error(d.getErrorCode(), d.getErrorMessage());
        List<AppliedGuideline> applied = d.getStatus() != JobStatus.COMPLETED ? List.of()
                : d.getGuidelines().stream().map(g -> new AppliedGuideline(g.getGuidelineId(), g.getText(), g.getSource(),
                        g.getApplied(), g.getNote())).toList();
        return new DraftView(d.getQuestionId(), d.getStatus(), error, chosen, sentences, omitted(d, current, userId), d.getFrame(),
                d.isUseContext(), d.getStatus() != JobStatus.ANALYZING && freeRegenerate(d, userId), applied,
                Times.seoul(d.getCreatedAt()),
                d.getUpdatedAt() == null ? null : Times.seoul(d.getUpdatedAt()));
    }

    // 경험이 그새 지워진 항목은 뺀다. 활동 · 경험 문장 · 답한 보완은 그 사용자의 경험에서 지금 값으로 읽는다 — 보완을 지우면 null 로 돌아간다
    private List<Omitted> omitted(Draft d, Map<String, SentenceEvidence.Ref> current, long userId) {
        Set<Long> attached = d.getOmitted().stream().map(DraftOmitted::getExperienceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Experience> owned = attached.isEmpty() ? Map.of()
                : experiences.findAllOwned(attached, userId).stream()
                        .collect(Collectors.toMap(Experience::getId, Function.identity()));
        return d.getOmitted().stream()
                .filter(o -> o.getExperienceId() == null
                        || current.containsKey(SentenceEvidence.key(EvidenceType.EXPERIENCE, o.getExperienceId())))
                .map(o -> omitted(o, o.getExperienceId() == null ? null : owned.get(o.getExperienceId())))
                .toList();
    }

    private static Omitted omitted(DraftOmitted o, Experience e) {
        if (e == null) {
            return new Omitted(o.getText(), o.getQuestion(), o.getExperienceId(), o.getClarificationId(), null,
                    o.getPlaceholder(), o.getSentenceIndex(), null, null, null, null);
        }
        Supplement answered = o.getClarificationId() == null ? null
                : e.getSupplements().stream().filter(s -> o.getClarificationId().equals(s.getClarificationId()))
                        .findFirst().orElse(null);
        return new Omitted(o.getText(), o.getQuestion(), o.getExperienceId(), o.getClarificationId(),
                answered == null ? null : answered.getId(), o.getPlaceholder(), o.getSentenceIndex(),
                e.getActivity().getId(), e.getActivity().getName(), e.getContent(),
                answered == null ? null : ExperienceResponse.SupplementResponse.from(answered));
    }

    /** 지금 다시 만들면 분석 횟수를 세지 않는가(S-77) — 시작할 때 세는 판단과 응답의 freeRegenerate 가 같은 코드를 쓴다. */
    private boolean freeRegenerate(Draft draft, long userId) {
        return answeredOmitted(draft) || actorDecidedSince(draft, userId);
    }

    // 지금 초안의 빠진 사실 중 답해서 보완이 생긴 것이 있나 — 있으면 다시 만들기를 세지 않는다(S-77)
    private boolean answeredOmitted(Draft draft) {
        if (draft == null) {
            return false;
        }
        List<Long> asked = draft.getOmitted().stream().map(DraftOmitted::getClarificationId).filter(Objects::nonNull)
                .toList();
        return !asked.isEmpty() && !supplements.findByClarificationIdIn(asked).isEmpty();
    }

    /**
     * 그 초안을 시작한 뒤 재료 경험의 주체가 UNKNOWN 에서 정해졌나(S-77). 보조 재료는 저장하지 않으므로, 켠 초안은 고른 경험의 활동 전체를 본다
     * — 주체도 활동 단위로 정한다(S-76).
     */
    private boolean actorDecidedSince(Draft draft, long userId) {
        if (draft == null || draft.getCreatedAt() == null) {
            return false;
        }
        List<Experience> chosen = experiences.findAllOwned(draft.getExperienceIds(), userId);
        List<Experience> material = !draft.isUseContext() || chosen.isEmpty() ? chosen
                : experiences.findRecentInActivities(
                        chosen.stream().map(e -> e.getActivity().getId()).distinct().toList(), userId);
        return material.stream().map(Experience::getActorDecidedAt)
                .anyMatch(at -> at != null && at.isAfter(draft.getCreatedAt()));
    }

    /** 카드의 「맞아요」(S-79) — 다시 눌러도 같다. 없거나 남의 것이거나 다시 만들어 사라진 문장이면 404. */
    @Transactional
    public void confirm(long sentenceId) {
        if (sentences.confirmOwned(sentenceId, CurrentUser.id()) == 0) {
            throw ApiException.notFound("DRAFT_SENTENCE_NOT_FOUND", "초안 문장 " + sentenceId + " 이(가) 없습니다");
        }
    }

    /** 카드의 「빼기」 · 「다시 넣기」(S-79) — 같은 값을 다시 보내도 같다. 없거나 남의 것이거나 다시 만들어 사라진 문장이면 404. */
    @Transactional
    public void setRemoved(long sentenceId, PatchBody body) {
        Boolean removed = body.bool("removed").map(PatchBody.Field::value)
                .orElseThrow(() -> ApiException.invalidRequest("removed 가 없습니다"));
        if (removed == null) {
            throw ApiException.invalidRequest("removed 는 true · false 여야 합니다");
        }
        if (sentences.setRemovedOwned(sentenceId, CurrentUser.id(), removed) == 0) {
            throw ApiException.notFound("DRAFT_SENTENCE_NOT_FOUND", "초안 문장 " + sentenceId + " 이(가) 없습니다");
        }
    }

    private static boolean useContext(Object value) {
        if (value != null && !(value instanceof Boolean)) {
            throw ApiException.invalidRequest("useContext 는 true · false 여야 합니다");
        }
        return value == null || (Boolean) value;
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

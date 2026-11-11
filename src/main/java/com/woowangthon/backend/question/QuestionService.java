package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.job.JobStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지원 문항 넣기 → 추천 작업 → 결과 저장 (ETC docs/contracts/question-api.md, ai-api.md 「POST /match」).
 * AI 호출 자체는 트랜잭션 밖(MatchRunner)에서 한다.
 */
@Service
public class QuestionService {

    static final int MAX_ORGANIZATION_LENGTH = 100;
    static final int MAX_TEXT_LENGTH = 5000;

    // question-api.md 「PATCH /api/questions/{questionId}」 targetLength(S-77)
    private static final int MIN_TARGET_LENGTH = 100;
    private static final int MAX_TARGET_LENGTH = 5000;

    // ai-api.md 「응답 규칙」
    private static final int MAX_REQUIREMENTS = 6;
    // 추천 0 ~ 8개, 서로 다른 활동 5개까지(S-84 · ETC#274). 2026-11-10 전에 넣은 문항의 0 ~ 3개는 그대로 읽는다
    private static final int MAX_RECOMMENDATIONS = 8;
    private static final int MAX_RECOMMENDED_ACTIVITIES = 5;
    private static final int MAX_CLARIFICATIONS = 3;

    private final QuestionRepository questions;
    private final ApplicationRepository applications;
    private final ExperienceRepository experiences;
    private final UsageLimiter limiter;
    private final DraftService drafts;
    private final AnswerService answers;
    private final AnswerDraftService answerDrafts;
    private final DraftRepository draftRows;
    private final GroundingCheckRepository checks;

    public QuestionService(QuestionRepository questions, ApplicationRepository applications,
            ExperienceRepository experiences, UsageLimiter limiter, DraftService drafts,
            AnswerService answers, AnswerDraftService answerDrafts, DraftRepository draftRows,
            GroundingCheckRepository checks) {
        this.draftRows = draftRows;
        this.checks = checks;
        this.questions = questions;
        this.applications = applications;
        this.experiences = experiences;
        this.limiter = limiter;
        this.drafts = drafts;
        this.answers = answers;
        this.answerDrafts = answerDrafts;
    }

    // organizationType · jobRole — 새로 만드는 지원의 것(선택). 문자열로 받아 목록 밖이면 400
    public record QuestionRequest(String organization, String organizationType, String jobRole, String text) {
    }

    /** 문항 하나짜리 지원을 새로 만들어 문항을 저장한다. 추천은 이 트랜잭션이 끝난 뒤 따로 시작한다. */
    @Transactional
    public QuestionResponses.Submitted submit(QuestionRequest request) {
        String text = checkText(request.text());
        String organization = checkOrganization(request.organization());
        OrganizationType organizationType = value(OrganizationType.class, request.organizationType(), "organizationType");
        JobRole jobRole = value(JobRole.class, request.jobRole(), "jobRole");
        limiter.consume(CurrentUser.id());
        Application application = applications.save(
                new Application(CurrentUser.id(), organization, organizationType, jobRole));
        Question question = questions.save(new Question(application, text));
        return new QuestionResponses.Submitted(question.getId(), application.getId(), question.getStatus());
    }

    @Transactional(readOnly = true)
    public QuestionResponses.Questions list() {
        return new QuestionResponses.Questions(questions.findByUserIdOrderByCreatedAtDescIdDesc(CurrentUser.id())
                .stream().map(QuestionResponses.Summary::from).toList());
    }

    @Transactional(readOnly = true)
    public QuestionResponses.Detail get(long questionId) {
        return get(CurrentUser.id(), questionId);
    }

    /** 외부 AI 는 세션 없이 사용자를 넘긴다. */
    @Transactional(readOnly = true)
    public QuestionResponses.Detail get(long userId, long questionId) {
        Question question = questions.findByIdAndUserId(questionId, userId)
                .orElseThrow(() -> notFound(questionId));
        return detail(question);
    }

    /**
     * 실패한 문항의 다시 시도(S-18) · 끝난 문항의 「경험 다시 찾기」(S-90) — 같은 문항 ID · 같은 글로 추천을 다시 돌린다.
     * 옛 요구사항 · 추천 · 추천의 되묻기만 지운다. 초안 · 근거 점검 · 확정 답 · 임시 글은 남는다 — 글이 그대로다(문항 글 고치기와 다른 점).
     */
    @Transactional
    public QuestionResponses.Detail retry(long questionId) {
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> notFound(questionId));
        // 초안 · 근거 점검도 잠가 본다 — 그것들이 도는 중에 추천이 바뀌지 않게(문항 글 고치기와 같은 조건)
        Optional<Draft> draft = draftRows.lockByQuestionId(questionId);
        Optional<GroundingCheck> check = checks.lockByQuestionId(questionId);
        if (question.getStatus() == JobStatus.ANALYZING
                || draft.filter(d -> d.getStatus() == JobStatus.ANALYZING).isPresent()
                || check.filter(c -> c.getStatus() == JobStatus.ANALYZING).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "QUESTION_ANALYZING",
                    "문항 " + questionId + " 은(는) 분석 중입니다. 끝난 뒤 다시 시도하세요");
        }
        // 한도면 429 — 옛 추천을 지우기 전에
        limiter.consume(question.getUserId());
        question.restart();
        questions.flush();
        return detail(question);
    }

    /** 고친 문항 — 글이 바뀌었으면 rematch(202, 추천을 다시 돌린다). */
    public record Updated(QuestionResponses.Detail question, boolean rematch) {
    }

    /**
     * 문항 글(S-89) · 유형(S-41) · 목표 글자 수(S-77)를 사용자가 고친다 — 보낸 필드만, 하나 이상. 유형 · 목표 글자 수만이면 분석 중 · 실패한
     * 문항도 되고 추천은 그대로다. 유형은 null 로 AI 값에 되돌리기가 없고, 목표 글자 수는 null 이면 비운다.
     * 글이 바뀌면 옛 추천 · 초안 · 근거 점검을 지우고 추천을 다시 돌린다(분석 1회). 확정 답 · 임시 글 · 보완은 남는다.
     */
    @Transactional
    public Updated update(long questionId, PatchBody body) {
        Optional<PatchBody.Field<String>> textField = body.string("text");
        Optional<PatchBody.Field<String>> typeField = body.string("questionType");
        Optional<PatchBody.Field<Long>> lengthField = body.id("targetLength");
        if (textField.isEmpty() && typeField.isEmpty() && lengthField.isEmpty()) {
            throw ApiException.invalidRequest("text · questionType · targetLength 중 하나 이상을 보내야 합니다");
        }
        String text = textField.map(f -> checkText(f.value()).strip()).orElse(null);
        QuestionType type = typeField.map(f -> value(QuestionType.class, f.value(), "questionType")).orElse(null);
        if (typeField.isPresent() && type == null) {
            throw ApiException.invalidRequest("questionType 은 null 일 수 없습니다");
        }
        Long length = lengthField.map(PatchBody.Field::value).orElse(null);
        if (length != null && (length < MIN_TARGET_LENGTH || length > MAX_TARGET_LENGTH)) {
            throw ApiException.invalidRequest(
                    "targetLength 는 " + MIN_TARGET_LENGTH + " ~ " + MAX_TARGET_LENGTH + " 사이여야 합니다");
        }
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> notFound(questionId));
        boolean rematch = text != null && !text.equals(question.getText().strip());
        if (rematch) {
            // 초안 · 근거 점검도 잠가 본다 — 그새 시작하거나 끝나며 지울 것을 다시 쓰지 못하게
            Optional<Draft> draft = draftRows.lockByQuestionId(questionId);
            Optional<GroundingCheck> check = checks.lockByQuestionId(questionId);
            if (question.getStatus() == JobStatus.ANALYZING
                    || draft.filter(d -> d.getStatus() == JobStatus.ANALYZING).isPresent()
                    || check.filter(c -> c.getStatus() == JobStatus.ANALYZING).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "QUESTION_ANALYZING",
                        "문항 " + questionId + " 은(는) 분석 중입니다. 끝난 뒤 다시 고치세요");
            }
            // 한도면 429 — 아무것도 바꾸기 전에
            limiter.consume(question.getUserId());
            draft.ifPresent(draftRows::delete);
            check.ifPresent(checks::delete);
            question.changeText(text);
        }
        if (type != null) {
            question.chooseType(type);
        }
        if (lengthField.isPresent()) {
            question.setTargetLength(length == null ? null : length.intValue());
        }
        questions.flush();
        return new Updated(detail(question), rematch);
    }

    /**
     * 문항 지우기(S-89) — 문항과 요구사항 · 추천 · 되묻기 · 초안 · 근거 점검 · 확정 답 · 임시 글 · 새 경험 후보를 지운다(DB 가 같이).
     * 보완(questionId → null) · 후보로 만든 경험(origin 그대로)은 남는다. 지원의 마지막 문항이면 409 — 지원 지우기를 쓴다.
     */
    @Transactional
    public void delete(long questionId) {
        long userId = CurrentUser.id();
        Question found = questions.findByIdAndUserId(questionId, userId).orElseThrow(() -> notFound(questionId));
        long applicationId = found.getApplication().getId();
        // 지원 · 문항 행을 잠가 문항 더하기 · 다른 문항 지우기와 겹쳐 마지막 문항을 지우지 않게
        applications.findForUpdate(applicationId, userId).orElseThrow(() -> notFound(questionId));
        List<Question> siblings = questions.lockByApplicationId(applicationId);
        if (siblings.stream().noneMatch(q -> q.getId() == questionId)) {
            throw notFound(questionId);
        }
        if (siblings.size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_QUESTION",
                    "지원의 마지막 문항은 지울 수 없습니다. 지원을 지우세요");
        }
        questions.delete(found);
        questions.flush();
    }

    /** AI 에 보낼 후보 — 그 사용자의 경험 전부를 근거 · 보완과 함께 지금 상태로(ADR-003). */
    @Transactional(readOnly = true)
    public MatchRequest matchRequest(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        // 지원의 직무 · 공고 요건 줄 전부를 맥락으로 싣는다(S-20 · S-42 · ETC#326) — 기존 · 새 · 더한 문항 · 다시 시도 모두. 지원처 종류는 싣지 않는다
        Application application = question.getApplication();
        JobRole jobRole = application == null ? null : application.getJobRole();
        List<MatchRequest.PostingLinePart> postingLines = application == null ? List.of()
                : application.getPostingLines().stream()
                        .map(l -> new MatchRequest.PostingLinePart(l.getSection(), l.getText(), l.getKsa())).toList();
        return new MatchRequest(new MatchRequest.QuestionPart(question.getOrganization(), question.getText(), jobRole,
                postingLines), experienceParts(question.getUserId()));
    }

    /** 사용자의 경험 전부를 ai-api.md 「POST /match」 experiences 모양으로 — 매칭과 꺼내기(/ask, S-29)가 같이 쓴다. */
    @Transactional(readOnly = true)
    public List<MatchRequest.ExperiencePart> experienceParts(long userId) {
        return experiences.findAllOfUser(userId).stream()
                .map(e -> new MatchRequest.ExperiencePart(e.getId(), e.getActivity().getId(), e.getActivity().getName(),
                        e.getActivity().getPeriod(), e.getContent(), e.getCompetencies(), e.getActor(),
                        e.getSourceStatus(),
                        e.getCitations().stream().map(c -> new MatchRequest.CitationPart(c.getId(), c.getQuote())).toList(),
                        // 앞 문항에서 답한 보완도 근거로 쓸 수 있게 싣는다(S-25) — 확인 전인 외부 AI 답은 뺀다(S-56)
                        e.getConfirmedSupplements().stream()
                                .map(s -> new MatchRequest.SupplementPart(s.getId(), s.getQuestion(), s.getAnswer())).toList()))
                .toList();
    }

    /**
     * AI 응답을 요구사항 · 추천 · 되묻기로 저장한다. 계약(ai-api.md 「응답 규칙」)을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고
     * AiCallException(AI_FAILED)을 던진다 — 고쳐서 쓰지 않는다. ID 는 보낸 요청(sent)에 있었는지로 확인한다.
     */
    @Transactional
    public void complete(long questionId, MatchRequest sent, MatchResponse result) {
        Question question = questions.lockById(questionId).orElseThrow();
        Map<Long, MatchRequest.ExperiencePart> candidates = sent.experiences().stream()
                .collect(Collectors.toMap(MatchRequest.ExperiencePart::id, Function.identity()));

        // AI 이슈 머지 전엔 없을 수 있다 — 없으면 null 로 두고 실패로 보지 않는다(BE#50). 목록 밖 값은 위반
        QuestionType questionType = result.questionType() == null ? null
                : Stream.of(QuestionType.values()).filter(t -> t.name().equals(result.questionType())).findFirst()
                        .orElseThrow(() -> AiCallException.invalidResponse(
                                "questionType 이 목록 밖 값입니다: " + result.questionType()));

        List<Requirement> requirements = requirements(result.requirements(), candidates);

        List<MatchResponse.RecommendationResult> recommended = orEmpty(result.recommendations());
        if (recommended.size() > MAX_RECOMMENDATIONS) {
            throw AiCallException.invalidResponse("recommendations 는 " + MAX_RECOMMENDATIONS + "개까지입니다");
        }
        Set<Long> recommendedIds = new HashSet<>();
        Set<Long> recommendedActivities = new HashSet<>();
        List<Recommendation> recommendations = new ArrayList<>();
        for (int position = 0; position < recommended.size(); position++) {
            MatchResponse.RecommendationResult r = recommended.get(position);
            MatchRequest.ExperiencePart experience = candidate(r.experienceId(), candidates, "recommendations[].experienceId");
            if (!recommendedIds.add(r.experienceId())) {
                throw AiCallException.invalidResponse("recommendations 에 같은 경험이 두 번 있습니다: " + r.experienceId());
            }
            if (recommendedActivities.add(experience.activityId()) && recommendedActivities.size() > MAX_RECOMMENDED_ACTIVITIES) {
                throw AiCallException.invalidResponse("recommendations 는 서로 다른 활동 " + MAX_RECOMMENDED_ACTIVITIES + "개까지입니다");
            }
            recommendations.add(new Recommendation(question, position, r.experienceId(),
                    required(r.reason(), "recommendations[].reason"), required(r.emphasis(), "recommendations[].emphasis"),
                    evidence(r.evidence(), experience), gaps(r.gaps())));
        }

        List<MatchResponse.ClarificationResult> asked = orEmpty(result.clarifications());
        if (asked.size() > MAX_CLARIFICATIONS) {
            throw AiCallException.invalidResponse("clarifications 는 " + MAX_CLARIFICATIONS + "개까지입니다");
        }
        List<Clarification> clarifications = new ArrayList<>();
        for (int position = 0; position < asked.size(); position++) {
            MatchResponse.ClarificationResult c = asked.get(position);
            // 답이 그 경험의 보완이 되므로 추천하지 않은 경험엔 묻지 않는다
            if (!recommendedIds.contains(c.experienceId())) {
                throw AiCallException.invalidResponse("clarifications[].experienceId 가 추천한 경험이 아닙니다: " + c.experienceId());
            }
            clarifications.add(new Clarification(question, position, c.experienceId(),
                    required(c.question(), "clarifications[].question")));
        }

        question.complete(questionType, requirements, recommendations, clarifications);
    }

    @Transactional
    public void fail(long questionId, String code, String message) {
        questions.lockById(questionId).orElseThrow().fail(code, message);
    }

    private QuestionResponses.Detail detail(Question question) {
        Set<Long> ids = new HashSet<>();
        question.getRequirements().forEach(r -> ids.addAll(r.getExperienceIds()));
        question.getRecommendations().forEach(r -> ids.add(r.getExperienceId()));
        question.getClarifications().forEach(c -> ids.add(c.getExperienceId()));
        Map<Long, Experience> existing = ids.isEmpty()
                ? Map.of()
                : experiences.findAllOwned(ids, question.getUserId()).stream()
                        .collect(Collectors.toMap(Experience::getId, Function.identity()));
        return QuestionResponses.Detail.from(question, existing, drafts.find(question), answers.find(question),
                answerDrafts.find(question));
    }

    private static List<Requirement> requirements(List<MatchResponse.RequirementResult> found,
            Map<Long, MatchRequest.ExperiencePart> candidates) {
        if (found == null || found.isEmpty() || found.size() > MAX_REQUIREMENTS) {
            throw AiCallException.invalidResponse("requirements 는 1 ~ " + MAX_REQUIREMENTS + "개여야 합니다");
        }
        List<Requirement> requirements = new ArrayList<>();
        for (MatchResponse.RequirementResult r : found) {
            RequirementStatus status = requirementStatus(r.status());
            // 같은 경험이 두 번 오면 한 번만 둔다
            Set<Long> ids = new LinkedHashSet<>();
            for (Long id : orEmpty(r.experienceIds())) {
                candidate(id, candidates, "requirements[].experienceIds");
                ids.add(id);
            }
            if (status == RequirementStatus.NOT_FOUND ? !ids.isEmpty() : ids.isEmpty()) {
                throw AiCallException.invalidResponse(
                        "requirements[].experienceIds 는 NOT_FOUND 면 [], 아니면 1개 이상이어야 합니다(" + status + ")");
            }
            requirements.add(new Requirement(required(r.text(), "requirements[].text"), status, List.copyOf(ids)));
        }
        return requirements;
    }

    // id 는 같은 경험의 근거 · 보완 중 하나여야 한다 — 지어낸 근거를 저장하지 않는다(S-20)
    public static List<Evidence> evidence(List<MatchResponse.EvidenceResult> found, MatchRequest.ExperiencePart experience) {
        Set<Long> citationIds = experience.citations().stream().map(MatchRequest.CitationPart::id).collect(Collectors.toSet());
        Set<Long> supplementIds = experience.supplements().stream().map(MatchRequest.SupplementPart::id)
                .collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<Evidence> evidence = new ArrayList<>();
        for (MatchResponse.EvidenceResult e : orEmpty(found)) {
            EvidenceType type = evidenceType(e.type());
            Set<Long> allowed = type == EvidenceType.CITATION ? citationIds : supplementIds;
            if (e.id() == null || !allowed.contains(e.id())) {
                throw AiCallException.invalidResponse("evidence 의 " + type + " " + e.id() + " 은(는) 경험 "
                        + experience.id() + " 의 것이 아닙니다");
            }
            if (!seen.add(type + ":" + e.id())) {
                throw AiCallException.invalidResponse("evidence 에 같은 것이 두 번 있습니다: " + type + " " + e.id());
            }
            evidence.add(new Evidence(type, e.id()));
        }
        return evidence;
    }

    private static List<String> gaps(List<String> found) {
        return orEmpty(found).stream().map(g -> required(g, "recommendations[].gaps[]")).toList();
    }

    private static MatchRequest.ExperiencePart candidate(Long id, Map<Long, MatchRequest.ExperiencePart> candidates,
            String field) {
        MatchRequest.ExperiencePart experience = id == null ? null : candidates.get(id);
        if (experience == null) {
            throw AiCallException.invalidResponse(field + " " + id + " 은(는) 보낸 경험이 아닙니다");
        }
        return experience;
    }

    private static RequirementStatus requirementStatus(String value) {
        return Stream.of(RequirementStatus.values()).filter(s -> s.name().equals(value)).findFirst()
                .orElseThrow(() -> AiCallException.invalidResponse(
                        "requirements[].status 는 SUPPORTED · PARTIAL · NOT_FOUND 중 하나여야 합니다: " + value));
    }

    // 추천엔 EXPERIENCE 가 없다(README 「값 목록」 evidenceType)
    private static EvidenceType evidenceType(String value) {
        return Stream.of(EvidenceType.CITATION, EvidenceType.SUPPLEMENT).filter(t -> t.name().equals(value)).findFirst()
                .orElseThrow(() -> AiCallException.invalidResponse(
                        "evidence[].type 은 CITATION · SUPPLEMENT 중 하나여야 합니다: " + value));
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AiCallException.invalidResponse(field + " 가 비었습니다");
        }
        return value;
    }

    // 한도는 Java String.length()(UTF-16) 기준 — FE(JS length)와 같은 숫자
    static String checkText(String text) {
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "문항이 " + MAX_TEXT_LENGTH + "자를 넘습니다");
        }
        return text;
    }

    // 선택 칸 — 공백뿐이면 안 넣은 것(null)으로 본다
    static String checkOrganization(String organization) {
        if (organization == null || organization.isBlank()) {
            return null;
        }
        if (organization.length() > MAX_ORGANIZATION_LENGTH) {
            throw ApiException.invalidRequest("organization 은 " + MAX_ORGANIZATION_LENGTH + "자까지입니다");
        }
        return organization;
    }

    /** 사용자가 보낸 값 목록 값 — null 이면 null(비움), 목록 밖이면 400 INVALID_REQUEST. */
    static <E extends Enum<E>> E value(Class<E> type, String value, String field) {
        if (value == null) {
            return null;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(value)) {
                return e;
            }
        }
        throw ApiException.invalidRequest(field + " 이(가) 목록 밖 값입니다: " + value);
    }

    private static ApiException notFound(long questionId) {
        return ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다");
    }
}

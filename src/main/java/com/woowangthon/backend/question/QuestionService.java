package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.job.JobStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    // ai-api.md 「응답 규칙」
    private static final int MAX_REQUIREMENTS = 6;
    private static final int MAX_RECOMMENDATIONS = 3;
    private static final int MAX_CLARIFICATIONS = 3;

    private final QuestionRepository questions;
    private final ExperienceRepository experiences;
    private final UsageLimiter limiter;

    public QuestionService(QuestionRepository questions, ExperienceRepository experiences, UsageLimiter limiter) {
        this.questions = questions;
        this.experiences = experiences;
        this.limiter = limiter;
    }

    public record QuestionRequest(String organization, String text) {
    }

    /** 문항을 저장한다. 추천은 이 트랜잭션이 끝난 뒤 따로 시작한다. */
    @Transactional
    public QuestionResponses.Submitted submit(QuestionRequest request) {
        String text = checkText(request.text());
        String organization = checkOrganization(request.organization());
        limiter.consume(CurrentUser.id());
        Question question = questions.save(new Question(CurrentUser.id(), organization, text));
        return new QuestionResponses.Submitted(question.getId(), question.getStatus());
    }

    @Transactional(readOnly = true)
    public QuestionResponses.Questions list() {
        return new QuestionResponses.Questions(questions.findByUserIdOrderByCreatedAtDescIdDesc(CurrentUser.id())
                .stream().map(QuestionResponses.Summary::from).toList());
    }

    @Transactional(readOnly = true)
    public QuestionResponses.Detail get(long questionId) {
        Question question = questions.findByIdAndUserId(questionId, CurrentUser.id())
                .orElseThrow(() -> notFound(questionId));
        return detail(question);
    }

    /** 실패한 문항만 다시 돌린다 — 같은 문항 ID 를 그대로 쓴다. */
    @Transactional
    public QuestionResponses.Detail retry(long questionId) {
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> notFound(questionId));
        if (question.getStatus() != JobStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "QUESTION_NOT_FAILED",
                    "문항 " + questionId + " 은(는) 실패 상태가 아닙니다(" + question.getStatus() + ")");
        }
        limiter.consume(question.getUserId());
        question.restart();
        questions.flush();
        return detail(question);
    }

    /** AI 에 보낼 후보 — 그 사용자의 경험 전부를 지금 상태로(ADR-003). */
    @Transactional(readOnly = true)
    public MatchRequest matchRequest(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        List<MatchRequest.ExperiencePart> candidates = experiences.findAllOfUser(question.getUserId()).stream()
                .map(e -> new MatchRequest.ExperiencePart(e.getId(), e.getActivity().getName(),
                        e.getActivity().getPeriod(), e.getContent(), e.getCompetencies(), e.getActor(),
                        e.getSourceStatus(),
                        e.getCitations().stream().map(c -> new MatchRequest.CitationPart(c.getId(), c.getQuote())).toList(),
                        // 보완 저장은 BE#23 — 그 전엔 어떤 경험에도 보완이 없다
                        List.of()))
                .toList();
        return new MatchRequest(new MatchRequest.QuestionPart(question.getOrganization(), question.getText()), candidates);
    }

    /**
     * AI 응답을 요구사항 · 추천 · 되묻기로 저장한다. 계약(ai-api.md 「응답 규칙」)을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고
     * AiCallException(AI_FAILED)을 던진다 — 고쳐서 쓰지 않는다. ID 는 보낸 요청(sent)에 있었는지로 확인한다.
     */
    @Transactional
    public void complete(long questionId, MatchRequest sent, MatchResponse result) {
        Question question = questions.findById(questionId).orElseThrow();
        Map<Long, MatchRequest.ExperiencePart> candidates = sent.experiences().stream()
                .collect(Collectors.toMap(MatchRequest.ExperiencePart::id, Function.identity()));

        List<Requirement> requirements = requirements(result.requirements(), candidates);

        List<MatchResponse.RecommendationResult> recommended = orEmpty(result.recommendations());
        if (recommended.size() > MAX_RECOMMENDATIONS) {
            throw AiCallException.invalidResponse("recommendations 는 " + MAX_RECOMMENDATIONS + "개까지입니다");
        }
        Set<Long> recommendedIds = new HashSet<>();
        List<Recommendation> recommendations = new ArrayList<>();
        for (int position = 0; position < recommended.size(); position++) {
            MatchResponse.RecommendationResult r = recommended.get(position);
            MatchRequest.ExperiencePart experience = candidate(r.experienceId(), candidates, "recommendations[].experienceId");
            if (!recommendedIds.add(r.experienceId())) {
                throw AiCallException.invalidResponse("recommendations 에 같은 경험이 두 번 있습니다: " + r.experienceId());
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

        question.complete(requirements, recommendations, clarifications);
    }

    @Transactional
    public void fail(long questionId, String code, String message) {
        questions.findById(questionId).orElseThrow().fail(code, message);
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
        return QuestionResponses.Detail.from(question, existing);
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
    private static List<Evidence> evidence(List<MatchResponse.EvidenceResult> found, MatchRequest.ExperiencePart experience) {
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

    private static EvidenceType evidenceType(String value) {
        return Stream.of(EvidenceType.values()).filter(t -> t.name().equals(value)).findFirst()
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
    private static String checkText(String text) {
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
    private static String checkOrganization(String organization) {
        if (organization == null || organization.isBlank()) {
            return null;
        }
        if (organization.length() > MAX_ORGANIZATION_LENGTH) {
            throw ApiException.invalidRequest("organization 은 " + MAX_ORGANIZATION_LENGTH + "자까지입니다");
        }
        return organization;
    }

    private static ApiException notFound(long questionId) {
        return ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다");
    }
}

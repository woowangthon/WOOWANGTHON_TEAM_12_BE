package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.PostingMatchRequest;
import com.woowangthon.backend.ai.PostingMatchResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.job.JobStatus;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 공고 기준 경험 찾기(S-93 · question-api.md 「공고 기준 경험 찾기」 · ai-api.md 「POST /posting-match」) — 요청 만들기 · 결과 저장 ·
 * 실패 · 다시 시도 · 응답 모양. AI 호출 자체는 트랜잭션 밖(PostingMatchRunner)에서 한다.
 */
@Service
public class PostingMatchService {

    // ai-api.md 「POST /posting-match」 응답 규칙
    private static final int MAX_EXPERIENCES_PER_LINE = 3;
    private static final int MAX_CLARIFICATIONS = 3;

    private final ApplicationRepository applications;
    private final ClarificationRepository clarifications;
    private final ExperienceRepository experiences;
    private final QuestionService questionService;
    private final UsageLimiter limiter;

    public PostingMatchService(ApplicationRepository applications, ClarificationRepository clarifications,
            ExperienceRepository experiences, QuestionService questionService, UsageLimiter limiter) {
        this.applications = applications;
        this.clarifications = clarifications;
        this.experiences = experiences;
        this.questionService = questionService;
        this.limiter = limiter;
    }

    /** AI 에 보낼 줄 · 직무 · 경험 전부(지금 상태로). 그새 지원이 지워졌거나 돌고 있지 않으면 비었다. */
    @Transactional(readOnly = true)
    public Optional<PostingMatchRequest> request(long applicationId) {
        return applications.findById(applicationId)
                .filter(a -> a.getPostingMatchStatus() == JobStatus.ANALYZING && !a.getPostingLines().isEmpty())
                .map(a -> new PostingMatchRequest(a.getJobRole(),
                        a.getPostingLines().stream()
                                .map(l -> new PostingMatchRequest.LinePart(l.getId(), l.getSection(), l.getText(), l.getKsa()))
                                .toList(),
                        questionService.experienceParts(a.getUserId())));
    }

    /**
     * AI 응답을 줄 판정 · 공고 되묻기로 저장한다. 계약을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고 AiCallException(AI_FAILED) —
     * 고쳐서 쓰지 않는다. ID 는 보낸 요청(sent)에 있었는지로 확인한다. 그새 지워졌거나 다시 시작된 작업이면 버린다.
     */
    @Transactional
    public void complete(long applicationId, PostingMatchRequest sent, PostingMatchResponse result) {
        Optional<Application> found = applications.lockById(applicationId)
                .filter(a -> a.getPostingMatchStatus() == JobStatus.ANALYZING);
        if (found.isEmpty()) {
            return;
        }
        Application application = found.get();
        Set<Long> candidates = sent.experiences().stream().map(MatchRequest.ExperiencePart::id).collect(Collectors.toSet());
        Map<Long, ApplicationPostingLine> lines = application.getPostingLines().stream()
                .collect(Collectors.toMap(ApplicationPostingLine::getId, Function.identity()));
        Set<Long> sentLines = sent.lines().stream().map(PostingMatchRequest.LinePart::id).collect(Collectors.toSet());

        // 줄마다 하나씩 — 빠짐 · 중복 · 요청에 없는 줄 금지
        List<PostingMatchResponse.LineResult> judged = orEmpty(result.lines());
        Map<Long, RequirementStatus> statuses = new HashMap<>();
        Map<Long, List<Long>> supporting = new HashMap<>();
        for (PostingMatchResponse.LineResult r : judged) {
            if (r == null || r.id() == null || !sentLines.contains(r.id()) || !lines.containsKey(r.id())) {
                throw AiCallException.invalidResponse("lines[].id " + (r == null ? null : r.id()) + " 은(는) 보낸 줄이 아닙니다");
            }
            if (statuses.containsKey(r.id())) {
                throw AiCallException.invalidResponse("lines 에 같은 줄이 두 번 있습니다: " + r.id());
            }
            RequirementStatus status = status(r.status());
            Set<Long> ids = new LinkedHashSet<>();
            for (Long id : orEmpty(r.experienceIds())) {
                if (id == null || !candidates.contains(id)) {
                    throw AiCallException.invalidResponse("lines[].experienceIds " + id + " 은(는) 보낸 경험이 아닙니다");
                }
                ids.add(id);
            }
            if (status == RequirementStatus.NOT_FOUND ? !ids.isEmpty()
                    : ids.isEmpty() || ids.size() > MAX_EXPERIENCES_PER_LINE) {
                throw AiCallException.invalidResponse("lines[].experienceIds 는 NOT_FOUND 면 [], 아니면 1 ~ "
                        + MAX_EXPERIENCES_PER_LINE + "개여야 합니다(" + status + ")");
            }
            statuses.put(r.id(), status);
            supporting.put(r.id(), List.copyOf(ids));
        }
        if (!statuses.keySet().equals(sentLines)) {
            throw AiCallException.invalidResponse("lines 는 보낸 줄마다 하나씩이어야 합니다");
        }

        // 되묻기 — PARTIAL 줄의, 그 줄을 받치는 경험에만
        List<PostingMatchResponse.ClarificationResult> asked = orEmpty(result.clarifications());
        if (asked.size() > MAX_CLARIFICATIONS) {
            throw AiCallException.invalidResponse("clarifications 는 " + MAX_CLARIFICATIONS + "개까지입니다");
        }
        for (PostingMatchResponse.ClarificationResult c : asked) {
            if (c == null || statuses.get(c.lineId()) != RequirementStatus.PARTIAL) {
                throw AiCallException.invalidResponse("clarifications[].lineId 가 PARTIAL 줄이 아닙니다: "
                        + (c == null ? null : c.lineId()));
            }
            if (!supporting.get(c.lineId()).contains(c.experienceId())) {
                throw AiCallException.invalidResponse("clarifications[].experienceId " + c.experienceId()
                        + " 은(는) 줄 " + c.lineId() + " 의 경험이 아닙니다");
            }
            if (c.question() == null || c.question().isBlank()) {
                throw AiCallException.invalidResponse("clarifications[].question 가 비었습니다");
            }
        }

        statuses.forEach((id, status) -> lines.get(id).judge(status, supporting.get(id)));
        for (int position = 0; position < asked.size(); position++) {
            PostingMatchResponse.ClarificationResult c = asked.get(position);
            clarifications.save(Clarification.forPostingLine(lines.get(c.lineId()), position, c.experienceId(), c.question()));
        }
        application.completePostingMatch();
    }

    /** 실패 + 이유. 그새 지워졌으면 그대로 둔다. */
    @Transactional
    public void fail(long applicationId, String code, String message) {
        applications.lockById(applicationId)
                .filter(a -> a.getPostingMatchStatus() == JobStatus.ANALYZING)
                .ifPresent(a -> a.failPostingMatch(code, message));
    }

    /**
     * 다시 시도 · 경험 다시 찾기 — 그때의 경험 · 보완으로 다시 돈다(분석 1회). 옛 판정 · 공고 되묻기를 같은 트랜잭션에서 지운다.
     * 답해서 생긴 보완은 남는다. 돌고 있으면 409, 한도면 429(아무것도 지우지 않는다).
     */
    @Transactional
    public Application retry(long applicationId) {
        Application application = applications.findForUpdate(applicationId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("APPLICATION_NOT_FOUND", "지원 " + applicationId + " 이(가) 없습니다"));
        if (application.getPostingLines().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTING_NOT_FOUND",
                    "지원 " + applicationId + " 에는 공고 요건 줄이 없습니다");
        }
        if (application.getPostingMatchStatus() == JobStatus.ANALYZING) {
            throw new ApiException(HttpStatus.CONFLICT, "POSTING_MATCH_ANALYZING",
                    "지원 " + applicationId + " 의 공고 기준 경험 찾기가 돌고 있습니다. 끝난 뒤 다시 시도하세요");
        }
        limiter.consume(application.getUserId());
        clarifications.deleteByApplicationPosting(applicationId);
        application.startPostingMatch();
        applications.flush();
        return application;
    }

    /**
     * Application.postingMatch — 줄이 없으면(또는 작업 전에 넣은 지원이면) null. 그새 지워진 경험은 줄의 experienceIds · 되묻기에서
     * 뺀다(빠져서 0개가 되어도 status 는 그대로).
     */
    @Transactional(readOnly = true)
    public QuestionResponses.PostingMatchView view(Application application) {
        if (application.getPostingLines().isEmpty() || application.getPostingMatchStatus() == null) {
            return null;
        }
        JobStatus status = application.getPostingMatchStatus();
        ErrorResponse.Error error = status == JobStatus.FAILED
                ? new ErrorResponse.Error(application.getPostingMatchErrorCode(), application.getPostingMatchErrorMessage())
                : null;
        if (status != JobStatus.COMPLETED) {
            return new QuestionResponses.PostingMatchView(status, error, List.of(), List.of());
        }
        List<Clarification> asked = clarifications.findByApplicationPosting(application.getId());
        Set<Long> ids = new HashSet<>();
        application.getPostingLines().forEach(l -> ids.addAll(l.getMatchExperienceIds()));
        asked.forEach(c -> ids.add(c.getExperienceId()));
        Map<Long, Experience> existing = ids.isEmpty()
                ? Map.of()
                : experiences.findAllOwned(ids, application.getUserId()).stream()
                        .collect(Collectors.toMap(Experience::getId, Function.identity()));
        List<QuestionResponses.PostingLineMatch> lines = application.getPostingLines().stream()
                .filter(l -> l.getMatchStatus() != null)
                .map(l -> new QuestionResponses.PostingLineMatch(l.getId(), l.getMatchStatus(),
                        l.getMatchExperienceIds().stream().filter(existing::containsKey).toList()))
                .toList();
        List<QuestionResponses.ClarificationResponse> views = new ArrayList<>();
        for (Clarification c : asked) {
            Experience e = existing.get(c.getExperienceId());
            if (e == null) {
                continue;
            }
            Long supplementId = e.getSupplements().stream().filter(s -> c.getId().equals(s.getClarificationId()))
                    .map(Supplement::getId).findFirst().orElse(null);
            views.add(new QuestionResponses.ClarificationResponse(c.getId(), c.getExperienceId(), c.getText(), supplementId,
                    c.getPostingLine().getId()));
        }
        return new QuestionResponses.PostingMatchView(status, null, lines, views);
    }

    private static RequirementStatus status(String value) {
        return Stream.of(RequirementStatus.values()).filter(s -> s.name().equals(value)).findFirst()
                .orElseThrow(() -> AiCallException.invalidResponse(
                        "lines[].status 는 SUPPORTED · PARTIAL · NOT_FOUND 중 하나여야 합니다: " + value));
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}

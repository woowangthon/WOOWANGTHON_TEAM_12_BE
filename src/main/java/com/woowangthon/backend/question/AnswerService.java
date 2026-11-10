package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.NewFactsRequest;
import com.woowangthon.backend.ai.NewFactsResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.ExperienceService;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정 답변 · 새 경험 후보 찾기 · 경험으로 저장 / 저장 안 함 (question-api.md 「확정 답변」, ai-api.md 「POST /new-facts」, SPEC S-48).
 * 문항마다 최신 하나. AI 호출 자체는 트랜잭션 밖(NewFactsRunner)에서 한다. 바꾸는 쪽은 모두 문항 행을 잠가 서로 겹치지 않는다.
 */
@Service
public class AnswerService {

    // ai-api.md 「응답 규칙」
    private static final int MAX_CANDIDATES = 5;

    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final NewFactCandidateRepository candidates;
    private final DraftRepository drafts;
    private final ExperienceRepository experiences;
    private final ActivityRepository activities;
    private final ExperienceService experienceService;
    private final UsageLimiter limiter;
    private final AnswerDraftService answerDrafts;

    public AnswerService(QuestionRepository questions, AnswerRepository answers, NewFactCandidateRepository candidates,
            DraftRepository drafts, ExperienceRepository experiences, ActivityRepository activities,
            ExperienceService experienceService, UsageLimiter limiter, AnswerDraftService answerDrafts) {
        this.questions = questions;
        this.answers = answers;
        this.candidates = candidates;
        this.drafts = drafts;
        this.experiences = experiences;
        this.activities = activities;
        this.experienceService = experienceService;
        this.limiter = limiter;
        this.answerDrafts = answerDrafts;
    }

    public record ConfirmRequest(String text) {
    }

    public record CandidateView(Long id, String sentence, String content, Actor actor, Long activityId,
            String activityName, NewFactStatus status, Long experienceId) {
    }

    public record NewFacts(JobStatus status, ErrorResponse.Error error, List<CandidateView> candidates,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record AnswerView(Long questionId, String text, OffsetDateTime confirmedAt, NewFacts newFacts) {
    }

    // PUT · POST 의 응답 { "answer": Answer }
    public record Result(AnswerView answer) {
    }

    /**
     * 답을 확정한다 — AI 를 부르지 않고 분석 횟수도 쓰지 않는다. 예전 답 · 찾기를 이것으로 바꾼다(PENDING 후보는 지우고, 만든 경험 ·
     * 「저장 안 함」 기억은 남는다). 찾기가 진행 중이면 409 로 예전 것을 그대로 둔다.
     */
    @Transactional
    public Result confirm(long questionId, ConfirmRequest request) {
        String text = checkText(request == null ? null : request.text());
        Question question = lockQuestion(questionId);
        Answer answer = answers.findByQuestionId(questionId).orElse(null);
        if (answer != null && answer.getNewFactsStatus() == JobStatus.ANALYZING) {
            throw inProgress(questionId);
        }
        if (answer == null) {
            answer = new Answer(questionId);
        }
        answer.confirm(text);
        answers.saveAndFlush(answer);
        // 확정하면 쓰는 중인 임시 글은 지운다(S-72)
        answerDrafts.discard(questionId);
        return new Result(view(answer, question.getUserId()));
    }

    /** 후보 찾기를 시작한다(분석 1회). 진행 중(409) · 한도(429)면 예전 찾기를 그대로 둔다. */
    @Transactional
    public Result startFinding(long questionId) {
        Question question = lockQuestion(questionId);
        Answer answer = answers.findByQuestionId(questionId).orElseThrow(() -> ApiException.notFound(
                "ANSWER_NOT_FOUND", "문항 " + questionId + " 에 확정한 답이 없습니다"));
        if (answer.getNewFactsStatus() == JobStatus.ANALYZING) {
            throw inProgress(questionId);
        }
        limiter.consume(question.getUserId());
        answer.startFinding();
        answers.saveAndFlush(answer);
        return new Result(view(answer, question.getUserId()));
    }

    /** 문항 응답에 싣는 확정 답 — 확정하지 않았으면 null. 문항을 읽는 트랜잭션 안에서 부른다. */
    public AnswerView find(Question question) {
        return answers.findByQuestionId(question.getId()).map(a -> view(a, question.getUserId())).orElse(null);
    }

    /**
     * AI 에 보낼 것 — 확정 답, 그 사용자의 경험 전부(원본에서 찾은 근거만) · 활동 전부를 지금 상태로, 그 문항의 저장된 초안 문장 전부 +
     * 「저장 안 함」 한 문장. 경험의 origin 은 싣지 않는다 — 그 경험도 USER_ENTERED 본문으로 다른 경험과 같이 간다.
     */
    @Transactional(readOnly = true)
    public NewFactsRequest request(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        Answer answer = answers.findByQuestionId(questionId).orElseThrow();
        List<MatchRequest.ExperiencePart> parts = experiences.findAllOfUser(question.getUserId()).stream()
                .map(SentenceEvidence::matchedPart).toList();
        List<NewFactsRequest.ActivityPart> activityParts = activities.findTimeline(question.getUserId()).stream()
                .map(a -> new NewFactsRequest.ActivityPart(a.getId(), a.getName(), a.getPeriod())).toList();
        Set<String> excluded = new LinkedHashSet<>();
        drafts.findByQuestionId(questionId)
                .ifPresent(d -> d.getSentences().forEach(s -> excluded.add(s.getText())));
        excluded.addAll(answer.getDismissed());
        return new NewFactsRequest(answer.getText(), parts, activityParts, List.copyOf(excluded));
    }

    /**
     * AI 응답을 후보로 저장한다. 계약(ai-api.md 「응답 규칙」)을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고 AiCallException(AI_FAILED)을
     * 던진다 — 고쳐서 쓰지 않는다. 그새 답이 바뀌었거나 재시작 정리로 실패가 됐으면 결과를 버린다.
     */
    @Transactional
    public void complete(long questionId, NewFactsRequest sent, NewFactsResponse result) {
        Answer answer = answers.lockByQuestionId(questionId).orElse(null);
        if (answer == null || answer.getNewFactsStatus() != JobStatus.ANALYZING
                || !answer.getText().equals(sent.text())) {
            return;
        }
        Set<Long> sentActivities = sent.activities().stream().map(NewFactsRequest.ActivityPart::id)
                .collect(Collectors.toSet());
        Set<String> excluded = Set.copyOf(sent.excludedSentences());

        List<NewFactsResponse.Candidate> found = result.candidates();
        if (found == null) {
            throw AiCallException.invalidResponse("candidates 가 없습니다");
        }
        if (found.size() > MAX_CANDIDATES) {
            throw AiCallException.invalidResponse("candidates 는 " + MAX_CANDIDATES + "개까지입니다");
        }
        List<NewFactCandidate> saved = new ArrayList<>();
        int cursor = 0;
        for (int position = 0; position < found.size(); position++) {
            NewFactsResponse.Candidate c = found.get(position);
            String at = "candidates[" + position + "]";
            if (c.sentence() == null || c.sentence().isBlank()) {
                throw AiCallException.invalidResponse(at + ".sentence 가 비었습니다");
            }
            // 원래 문장은 확정 글의 이어진 일부이고 글의 순서대로다 — 문장 하나에 후보 하나
            int index = sent.text().indexOf(c.sentence(), cursor);
            if (index < 0) {
                throw AiCallException.invalidResponse(at + ".sentence 가 확정 글에 순서대로 없습니다");
            }
            cursor = index + c.sentence().length();
            if (excluded.contains(c.sentence())) {
                throw AiCallException.invalidResponse(at + ".sentence 는 빼야 할 문장입니다");
            }
            if (c.content() == null || c.content().isBlank()) {
                throw AiCallException.invalidResponse(at + ".content 가 비었습니다");
            }
            Actor actor = Stream.of(Actor.values()).filter(a -> a.name().equals(c.actor())).findFirst()
                    .orElseThrow(() -> AiCallException.invalidResponse(
                            at + ".actor 는 ME · TEAM · UNKNOWN 중 하나여야 합니다: " + c.actor()));
            if (c.activityId() != null && !sentActivities.contains(c.activityId())) {
                throw AiCallException.invalidResponse(at + ".activityId 는 보낸 활동이 아닙니다: " + c.activityId());
            }
            saved.add(new NewFactCandidate(answer, position, c.sentence(), c.content(), actor, c.activityId()));
        }
        answer.complete(saved);
    }

    @Transactional
    public void fail(long questionId, String code, String message) {
        answers.lockByQuestionId(questionId).ifPresent(a -> {
            if (a.getNewFactsStatus() == JobStatus.ANALYZING) {
                a.fail(code, message);
            }
        });
    }

    /**
     * 후보를 경험으로 저장한다 — 「경험 직접 추가」와 같은 경험(USER_ENTERED · competenciesBy USER · 근거 없음, 활동 맨 뒤)에 origin 만 더한다.
     * 이미 정한 후보면 409 — 경험을 또 만들지 않는다.
     */
    @Transactional
    public ExperienceResponse save(long candidateId, PatchBody body) {
        Long activityId = body.id("activityId").map(PatchBody.Field::value).orElse(null);
        if (activityId == null) {
            throw ApiException.invalidRequest("activityId 가 없습니다");
        }
        NewFactCandidate candidate = lockPending(candidateId);
        Question question = questions.findById(candidate.getAnswer().getQuestionId()).orElseThrow();
        Experience experience = experienceService.create(activityId, body, question);
        candidate.getAnswer().saveCandidate(candidate, experience.getId());
        answers.flush();
        return ExperienceResponse.from(experience);
    }

    /** 저장 안 함 — 그 문장은 이 문항의 다음 찾기부터 빠진다(글자가 같을 때만). */
    @Transactional
    public void dismiss(long candidateId) {
        NewFactCandidate candidate = lockPending(candidateId);
        candidate.getAnswer().dismissCandidate(candidate);
        answers.flush();
    }

    /** 후보의 문항 행을 잠근 뒤 후보를 읽는다 — 다시 확정 · 다시 찾기와 겹쳐 지워진 후보에 경험을 만들지 않게. */
    private NewFactCandidate lockPending(long candidateId) {
        long userId = CurrentUser.id();
        Long questionId = candidates.findQuestionId(candidateId, userId).orElseThrow(() -> candidateNotFound(candidateId));
        questions.findForUpdate(questionId, userId).orElseThrow(() -> candidateNotFound(candidateId));
        NewFactCandidate candidate = candidates.findById(candidateId).orElseThrow(() -> candidateNotFound(candidateId));
        if (candidate.getStatus() != NewFactStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "NEW_FACT_ALREADY_DECIDED",
                    "후보 " + candidateId + " 은(는) 이미 정했습니다(" + candidate.getStatus() + ")");
        }
        return candidate;
    }

    /** 후보의 활동 이름은 지금 이름으로 — 그새 지워지거나 합쳐져 사라진 활동이면 activityId · activityName 둘 다 null. */
    private AnswerView view(Answer a, long userId) {
        NewFacts newFacts = null;
        if (a.getNewFactsStatus() != null) {
            Set<Long> activityIds = a.getCandidates().stream().map(NewFactCandidate::getActivityId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, Activity> current = activityIds.isEmpty() ? Map.of()
                    : activities.findAllById(activityIds).stream().filter(x -> x.getUserId().equals(userId))
                            .collect(Collectors.toMap(Activity::getId, Function.identity()));
            List<CandidateView> views = a.getCandidates().stream().map(c -> {
                Activity activity = c.getActivityId() == null ? null : current.get(c.getActivityId());
                return new CandidateView(c.getId(), c.getSentence(), c.getContent(), c.getActor(),
                        activity == null ? null : activity.getId(), activity == null ? null : activity.getName(),
                        c.getStatus(), c.getExperienceId());
            }).toList();
            ErrorResponse.Error error = a.getErrorCode() == null ? null
                    : new ErrorResponse.Error(a.getErrorCode(), a.getErrorMessage());
            newFacts = new NewFacts(a.getNewFactsStatus(), error, views, Times.seoul(a.getNewFactsCreatedAt()),
                    Times.seoul(a.getNewFactsUpdatedAt()));
        }
        return new AnswerView(a.getQuestionId(), a.getText(), Times.seoul(a.getConfirmedAt()), newFacts);
    }

    // 한도는 근거 점검과 같은 셈(String.length())
    private static String checkText(String text) {
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > QuestionService.MAX_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "확정할 답이 " + QuestionService.MAX_TEXT_LENGTH + "자를 넘습니다");
        }
        return text;
    }

    private Question lockQuestion(long questionId) {
        return questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다"));
    }

    private static ApiException inProgress(long questionId) {
        return new ApiException(HttpStatus.CONFLICT, "NEW_FACTS_IN_PROGRESS",
                "문항 " + questionId + " 의 새 경험 후보를 아직 찾는 중입니다");
    }

    private static ApiException candidateNotFound(long candidateId) {
        return ApiException.notFound("NEW_FACT_NOT_FOUND", "후보 " + candidateId + " 이(가) 없습니다");
    }

    // ── 비슷한 확정 답변(S-43, question-api.md 「비슷한 확정 답변」) ─────────────────────────────────────
    // 참고로만 보이는 목록이다 — 근거가 아니라 어떤 AI 요청에도 싣지 않고, AI 를 부르지 않고, 분석 횟수를 쓰지 않는다.
    // 「비슷하다」는 글이 아니라 꼬리표 셋 — 문항 유형(question) · 직무 · 지원처 종류(application) — 을 지금 값으로 비교한다.

    static final int MAX_SIMILAR = 5;

    public record SimilarAnswer(Long questionId, String questionText, QuestionType questionType, Long applicationId,
            String organization, OrganizationType organizationType, JobRole jobRole, String text,
            OffsetDateTime confirmedAt) {
    }

    public record SimilarAnswers(List<SimilarAnswer> answers) {
    }

    /**
     * 유형 → 직무 → 종류 순으로 앞에서부터 같은 것을 비교해 가까운 것부터(계약 표 1 ~ 7 차례와 같다), 같으면 확정이 최근인 것부터.
     * 셋 다 같지 않으면 뺀다. 추천 상태와 상관없다 — 유형이 아직 없으면 직무 · 종류로만 고른다.
     */
    @Transactional(readOnly = true)
    public SimilarAnswers similar(long questionId) {
        long userId = CurrentUser.id();
        Question base = questions.findByIdAndUserId(questionId, userId)
                .orElseThrow(() -> ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다"));
        Application baseApplication = base.getApplication();

        record Scored(SimilarAnswer answer, boolean type, boolean role, boolean kind) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Object[] row : answers.findConfirmedOfUserExcept(userId, questionId)) {
            Answer a = (Answer) row[0];
            Question q = (Question) row[1];
            Application ap = (Application) row[2];
            boolean type = same(base.getQuestionType(), q.getQuestionType());
            boolean role = same(baseApplication.getJobRole(), ap.getJobRole());
            boolean kind = same(baseApplication.getOrganizationType(), ap.getOrganizationType());
            if (!type && !role && !kind) {
                continue;
            }
            scored.add(new Scored(new SimilarAnswer(q.getId(), q.getText(), q.getQuestionType(), ap.getId(),
                    ap.getOrganization(), ap.getOrganizationType(), ap.getJobRole(), a.getText(),
                    Times.seoul(a.getConfirmedAt())), type, role, kind));
        }
        // true 가 앞 — (유형, 직무, 종류) 를 앞에서부터 비교하면 계약 표의 차례가 된다
        Comparator<Scored> closest = Comparator.comparing(Scored::type).reversed()
                .thenComparing(Comparator.comparing(Scored::role).reversed())
                .thenComparing(Comparator.comparing(Scored::kind).reversed())
                .thenComparing(s -> s.answer().confirmedAt(), Comparator.reverseOrder())
                .thenComparing(s -> s.answer().questionId(), Comparator.reverseOrder());
        return new SimilarAnswers(scored.stream().sorted(closest).limit(MAX_SIMILAR).map(Scored::answer).toList());
    }

    // 둘 다 값이 있고 같을 때만 같다. null(값 없음) · OTHER(맞는 값 없음)는 어느 것과도 같지 않다
    private static <E extends Enum<E>> boolean same(E base, E other) {
        return base != null && base == other && !"OTHER".equals(base.name());
    }
}

package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.common.PostingUrls;
import com.woowangthon.backend.job.JobStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지원(지원처 하나 + 문항 여러 개) 넣기 · 문항 더하기 · 목록 · 지원 하나 (ETC docs/contracts/question-api.md).
 * 추천은 지금처럼 문항마다 따로 돈다 — 저장이 끝난 뒤 문항마다 MatchRunner 를 부른다(ApplicationController).
 */
@Service
public class ApplicationService {

    static final int MAX_QUESTIONS_PER_SUBMIT = 10;
    static final int MAX_QUESTIONS_PER_APPLICATION = 20;
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final ApplicationRepository applications;
    private final QuestionRepository questions;
    private final UsageLimiter limiter;
    private final ClarificationRepository clarifications;
    private final DraftRepository drafts;
    private final GroundingCheckRepository groundingChecks;
    private final AnswerRepository answers;

    public ApplicationService(ApplicationRepository applications, QuestionRepository questions, UsageLimiter limiter,
            ClarificationRepository clarifications, DraftRepository drafts, GroundingCheckRepository groundingChecks,
            AnswerRepository answers) {
        this.applications = applications;
        this.questions = questions;
        this.limiter = limiter;
        this.clarifications = clarifications;
        this.drafts = drafts;
        this.groundingChecks = groundingChecks;
        this.answers = answers;
    }

    public record QuestionText(String text) {
    }

    // organizationType · jobRole — 선택. 문자열로 받아 목록 밖이면 400(S-42). postingUrl — 선택, 빼면 null(S-46)
    public record ApplicationRequest(String organization, String organizationType, String jobRole, String postingUrl,
            List<QuestionText> questions) {
    }

    /** 외부 AI 가 만든 지원을 되돌려도 되나 볼 때 쓰는 지금 상태. touched — 사용자가 손댔다(계약 「변경 기록」 CHANGED_AFTER). */
    public record ApplicationState(int questionCount, int analyzingCount, boolean touched) {
    }

    @Transactional
    public QuestionResponses.ApplicationSubmitted submit(ApplicationRequest request) {
        return submit(CurrentUser.id(), request);
    }

    /** 지원과 문항을 넣은 순서대로 저장한다. 남은 분석 횟수가 문항 수보다 적으면 아무것도 저장하지 않는다(429). 외부 AI 는 세션 없이 사용자를 넘긴다. */
    @Transactional
    public QuestionResponses.ApplicationSubmitted submit(long userId, ApplicationRequest request) {
        List<QuestionText> asked = request.questions();
        if (asked == null || asked.isEmpty() || asked.size() > MAX_QUESTIONS_PER_SUBMIT) {
            throw ApiException.invalidRequest("questions 는 1 ~ " + MAX_QUESTIONS_PER_SUBMIT + "개여야 합니다");
        }
        List<String> texts = new ArrayList<>();
        for (QuestionText q : asked) {
            texts.add(QuestionService.checkText(q == null ? null : q.text()));
        }
        String organization = QuestionService.checkOrganization(request.organization());
        OrganizationType organizationType = QuestionService.value(OrganizationType.class, request.organizationType(),
                "organizationType");
        JobRole jobRole = QuestionService.value(JobRole.class, request.jobRole(), "jobRole");
        String postingUrl = request.postingUrl();
        if (postingUrl != null && !PostingUrls.isValid(postingUrl)) {
            throw ApiException.invalidRequest(
                    "postingUrl 은 " + PostingUrls.MAX_LENGTH + "자 이하의 http(s):// 주소여야 합니다");
        }
        limiter.consume(userId, texts.size());

        Application application = applications.save(
                new Application(userId, organization, organizationType, jobRole, postingUrl));
        // 하나씩 저장해 ID 가 넣은 순서대로 커지게 한다 — 지원 안의 문항 순서는 ID 순서다
        List<Long> questionIds = new ArrayList<>();
        for (String text : texts) {
            questionIds.add(questions.saveAndFlush(new Question(application, text)).getId());
        }
        return new QuestionResponses.ApplicationSubmitted(application.getId(), questionIds);
    }

    /** 지원에 문항 하나를 더한다 — 지원처 이름은 그 지원의 것. */
    @Transactional
    public QuestionResponses.Submitted addQuestion(long applicationId, QuestionText request) {
        Application application = applications.findForUpdate(applicationId, CurrentUser.id())
                .orElseThrow(() -> notFound(applicationId));
        String text = QuestionService.checkText(request.text());
        if (questions.countByApplicationId(applicationId) >= MAX_QUESTIONS_PER_APPLICATION) {
            throw ApiException.invalidRequest("지원 하나의 문항은 " + MAX_QUESTIONS_PER_APPLICATION + "개까지입니다");
        }
        limiter.consume(application.getUserId());
        Question question = questions.save(new Question(application, text));
        return new QuestionResponses.Submitted(question.getId(), application.getId(), question.getStatus());
    }

    /**
     * 지원처 종류 · 직무(S-42) · 상태(S-36) · 낸 달(S-37)을 바꾼다 — 보낸 필드만. 종류 · 직무는 null 이면 비우고,
     * 상태 · 낸 달은 null 을 받지 않는다. 둘 다 보내면 보낸 달이 이긴다. 추천은 다시 돌지 않는다.
     */
    @Transactional
    public QuestionResponses.ApplicationResponse update(long applicationId, PatchBody body) {
        var organizationType = body.string("organizationType")
                .map(f -> new PatchBody.Field<>(QuestionService.value(OrganizationType.class, f.value(), "organizationType")));
        var jobRole = body.string("jobRole")
                .map(f -> new PatchBody.Field<>(QuestionService.value(JobRole.class, f.value(), "jobRole")));
        var status = body.string("applicationStatus").map(f -> applicationStatus(f.value()));
        var month = body.string("submittedMonth").map(f -> checkMonth(f.value()));
        if (organizationType.isEmpty() && jobRole.isEmpty() && status.isEmpty() && month.isEmpty()) {
            throw ApiException.invalidRequest(
                    "organizationType · jobRole · applicationStatus · submittedMonth 중 하나는 보내야 합니다");
        }
        Application application = applications.findForUpdate(applicationId, CurrentUser.id())
                .orElseThrow(() -> notFound(applicationId));
        organizationType.ifPresent(f -> application.changeOrganizationType(f.value()));
        jobRole.ifPresent(f -> application.changeJobRole(f.value()));
        status.ifPresent(application::changeStatus);
        if (month.isPresent()) {
            if (application.getApplicationStatus() == ApplicationStatus.WRITING) {
                throw ApiException.invalidRequest("작성 중인 지원에는 submittedMonth 를 보낼 수 없습니다");
            }
            application.changeSubmittedMonth(month.get());
        }
        return QuestionResponses.ApplicationResponse.from(application);
    }

    @Transactional(readOnly = true)
    public QuestionResponses.Applications list() {
        return new QuestionResponses.Applications(applications.findByUserIdOrderByCreatedAtDescIdDesc(CurrentUser.id())
                .stream().map(QuestionResponses.ApplicationResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public QuestionResponses.ApplicationResponse get(long applicationId) {
        return QuestionResponses.ApplicationResponse.from(applications.findByIdAndUserId(applicationId, CurrentUser.id())
                .orElseThrow(() -> notFound(applicationId)));
    }

    /** 지원들의 지금 상태 — 지워진 지원은 결과에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, ApplicationState> states(long userId, Collection<Long> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        List<Application> found = applications.findAllWithQuestions(applicationIds, userId);
        List<Long> questionIds = found.stream().flatMap(a -> a.getQuestions().stream()).map(Question::getId).toList();
        Set<Long> worked = new HashSet<>(clarifications.findAnsweredQuestionIds(questionIds));
        worked.addAll(drafts.findQuestionIds(questionIds));
        worked.addAll(groundingChecks.findQuestionIds(questionIds));
        worked.addAll(answers.findQuestionIds(questionIds));
        return found.stream().collect(Collectors.toMap(Application::getId, a -> state(a, worked)));
    }

    private static ApplicationState state(Application application, Set<Long> worked) {
        List<Question> asked = application.getQuestions();
        return new ApplicationState(asked.size(),
                (int) asked.stream().filter(q -> q.getStatus() == JobStatus.ANALYZING).count(),
                application.getApplicationStatus() != ApplicationStatus.WRITING
                        || asked.stream().anyMatch(q -> worked.contains(q.getId())));
    }

    /** 상태를 보기 전에 지원 · 문항 행을 잠근다 — 본 뒤에 문항 더하기 · 다시 시도 · 초안이 끼어들지 못한다. 없으면 그대로 둔다. */
    @Transactional
    public void lock(long userId, long applicationId) {
        applications.findForUpdate(applicationId, userId).ifPresent(a -> questions.lockByApplicationId(applicationId));
    }

    /** 지원과 문항 · 요구사항 · 추천 · 되묻기를 지운다. question.application_id 는 저절로 지워지지 않아 문항부터 지운다. */
    @Transactional
    public void remove(long userId, long applicationId) {
        Application application = applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> notFound(applicationId));
        questions.deleteAll(application.getQuestions());
        questions.flush();
        applications.delete(application);
    }

    private static ApplicationStatus applicationStatus(String value) {
        ApplicationStatus status = QuestionService.value(ApplicationStatus.class, value, "applicationStatus");
        if (status == null) {
            throw ApiException.invalidRequest("applicationStatus 는 null 일 수 없습니다");
        }
        return status;
    }

    private static String checkMonth(String value) {
        if (value == null || !MONTH.matcher(value).matches()) {
            throw ApiException.invalidRequest("submittedMonth 는 YYYY-MM 이어야 합니다");
        }
        return value;
    }

    private static ApiException notFound(long applicationId) {
        return ApiException.notFound("APPLICATION_NOT_FOUND", "지원 " + applicationId + " 이(가) 없습니다");
    }
}

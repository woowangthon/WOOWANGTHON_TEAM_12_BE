package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import java.util.ArrayList;
import java.util.List;
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

    private final ApplicationRepository applications;
    private final QuestionRepository questions;
    private final UsageLimiter limiter;

    public ApplicationService(ApplicationRepository applications, QuestionRepository questions, UsageLimiter limiter) {
        this.applications = applications;
        this.questions = questions;
        this.limiter = limiter;
    }

    public record QuestionText(String text) {
    }

    public record ApplicationRequest(String organization, List<QuestionText> questions) {
    }

    /** 지원과 문항을 넣은 순서대로 저장한다. 남은 분석 횟수가 문항 수보다 적으면 아무것도 저장하지 않는다(429). */
    @Transactional
    public QuestionResponses.ApplicationSubmitted submit(ApplicationRequest request) {
        List<QuestionText> asked = request.questions();
        if (asked == null || asked.isEmpty() || asked.size() > MAX_QUESTIONS_PER_SUBMIT) {
            throw ApiException.invalidRequest("questions 는 1 ~ " + MAX_QUESTIONS_PER_SUBMIT + "개여야 합니다");
        }
        List<String> texts = new ArrayList<>();
        for (QuestionText q : asked) {
            texts.add(QuestionService.checkText(q == null ? null : q.text()));
        }
        String organization = QuestionService.checkOrganization(request.organization());
        limiter.consume(CurrentUser.id(), texts.size());

        Application application = applications.save(new Application(CurrentUser.id(), organization));
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

    private static ApiException notFound(long applicationId) {
        return ApiException.notFound("APPLICATION_NOT_FOUND", "지원 " + applicationId + " 이(가) 없습니다");
    }
}

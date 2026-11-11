package com.woowangthon.backend.export;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionResponses;
import com.woowangthon.backend.question.QuestionService;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Markdown 내보내기(ETC docs/contracts/export-api.md · S-33) — 지금 데이터를 읽어 글 한 장으로 옮긴다. 저장 · AI 호출 · 분석 횟수 없음.
 * 세션 주인 것만 읽고, 남의 활동 · 지원은 없는 것과 같이 404.
 */
@Service
public class ExportService {

    private final ActivityRepository activities;
    private final ApplicationRepository applications;
    private final QuestionService questions;

    public ExportService(ActivityRepository activities, ApplicationRepository applications, QuestionService questions) {
        this.activities = activities;
        this.applications = applications;
        this.questions = questions;
    }

    record Exported(ExportFileName fileName, String markdown) {
    }

    @Transactional(readOnly = true)
    public Exported all(long userId) {
        OffsetDateTime now = now();
        return new Exported(ExportFileName.all(now.toLocalDate()),
                ExportMarkdown.all(activities.findTimeline(userId), now));
    }

    @Transactional(readOnly = true)
    public Exported activity(long userId, long activityId) {
        Activity activity = activities.findByIdAndUserId(activityId, userId)
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));
        OffsetDateTime now = now();
        return new Exported(ExportFileName.activity(activityId, activity.getName(), now.toLocalDate()),
                ExportMarkdown.activity(activity, now));
    }

    /** 문항마다 GET /api/questions/{id} 와 같은 응답을 받아 쓴다 — 그새 지워진 경험은 추천에서 빠진 채로. */
    @Transactional(readOnly = true)
    public Exported application(long userId, long applicationId) {
        Application application = applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> ApiException.notFound("APPLICATION_NOT_FOUND", "지원 " + applicationId + " 이(가) 없습니다"));
        List<QuestionResponses.Detail> details = application.getQuestions().stream()
                .map(Question::getId)
                .map(id -> questions.get(userId, id))
                .toList();
        OffsetDateTime now = now();
        return new Exported(ExportFileName.application(applicationId, application.getOrganization(), now.toLocalDate()),
                ExportMarkdown.application(application, details, now));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(Times.SEOUL).truncatedTo(ChronoUnit.SECONDS);
    }
}

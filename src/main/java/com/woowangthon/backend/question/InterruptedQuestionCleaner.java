package com.woowangthon.backend.question;

import com.woowangthon.backend.job.JobStatus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 추천 도중 꺼지면 문항이 「분석 중」에 멈춰 FE 가 끝없이 기다린다. 시작할 때 그런 문항을 실패로 돌려
 * 「다시 시도」할 수 있게 한다 — InterruptedJobCleaner 와 같다(서버 한 대, ADR-004).
 */
@Component
public class InterruptedQuestionCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedQuestionCleaner.class);

    private final QuestionRepository questions;
    private final GroundingCheckRepository checks;
    private final DraftRepository drafts;
    private final AnswerRepository answers;
    private final ApplicationRepository applications;

    public InterruptedQuestionCleaner(QuestionRepository questions, GroundingCheckRepository checks,
            DraftRepository drafts, AnswerRepository answers, ApplicationRepository applications) {
        this.applications = applications;
        this.questions = questions;
        this.checks = checks;
        this.drafts = drafts;
        this.answers = answers;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Question> interrupted = questions.findByStatus(JobStatus.ANALYZING);
        interrupted.forEach(q -> q.fail("INTERNAL_ERROR", "서버가 다시 시작되어 추천이 끊겼습니다"));
        if (!interrupted.isEmpty()) {
            log.warn("추천 중에 끊긴 문항 {}개를 실패로 돌렸습니다", interrupted.size());
        }
        // 근거 점검도 같다(S-45) — 「다시 점검」은 같은 POST 라 실패로만 돌리면 된다
        List<GroundingCheck> interruptedChecks = checks.findByStatus(JobStatus.ANALYZING);
        interruptedChecks.forEach(c -> c.fail("INTERNAL_ERROR", "서버가 다시 시작되어 근거 점검이 끊겼습니다"));
        if (!interruptedChecks.isEmpty()) {
            log.warn("점검 중에 끊긴 근거 점검 {}개를 실패로 돌렸습니다", interruptedChecks.size());
        }
        // 답변 초안도 같다(S-27)
        List<Draft> interruptedDrafts = drafts.findByStatus(JobStatus.ANALYZING);
        interruptedDrafts.forEach(d -> d.fail("INTERNAL_ERROR", "서버가 다시 시작되어 답변 초안이 끊겼습니다"));
        if (!interruptedDrafts.isEmpty()) {
            log.warn("만드는 중에 끊긴 답변 초안 {}개를 실패로 돌렸습니다", interruptedDrafts.size());
        }
        // 새 경험 후보 찾기도 같다(S-48) — 「다시 찾기」는 같은 POST
        List<Answer> interruptedAnswers = answers.findByNewFactsStatus(JobStatus.ANALYZING);
        interruptedAnswers.forEach(a -> a.fail("INTERNAL_ERROR", "서버가 다시 시작되어 새 경험 후보 찾기가 끊겼습니다"));
        if (!interruptedAnswers.isEmpty()) {
            log.warn("찾는 중에 끊긴 새 경험 후보 찾기 {}개를 실패로 돌렸습니다", interruptedAnswers.size());
        }
        // 공고 기준 경험 찾기도 같다(S-93) — 다시 시도는 …/posting-match/retry
        List<Application> interruptedPostings = applications.findByPostingMatchStatus(JobStatus.ANALYZING);
        interruptedPostings.forEach(a -> a.failPostingMatch("INTERNAL_ERROR", "서버가 다시 시작되어 공고 기준 경험 찾기가 끊겼습니다"));
        if (!interruptedPostings.isEmpty()) {
            log.warn("찾는 중에 끊긴 공고 기준 경험 찾기 {}개를 실패로 돌렸습니다", interruptedPostings.size());
        }
    }
}

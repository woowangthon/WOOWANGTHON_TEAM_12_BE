package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import com.woowangthon.backend.job.AnalysisService;
import com.woowangthon.backend.job.JobResponses;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.ApplicationService;
import com.woowangthon.backend.question.ClarificationService;
import com.woowangthon.backend.question.QuestionResponses;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 AI 쓰기(계약 「외부 쓰기 도구」 · ADR-010) — 쓰기와 변경 기록이 한 트랜잭션이다. 사용자는 세션이 아니라 토큰으로 받는다.
 * 분석처럼 뒤에서 도는 일은 이 메서드가 돌아온 뒤(커밋 뒤) 도구가 시작한다.
 */
@Service
public class McpWriteService {

    // 쓰기 하루 상한을 같이 쓰는 도구(계약 update_experience) — 분석 한도를 쓰지 않는 쓰기
    private static final List<McpChangeType> LIMITED = List.of(McpChangeType.UPDATE_EXPERIENCE,
            McpChangeType.ANSWER_CLARIFICATION);

    private final AnalysisService analyses;
    private final ExperienceRepository experiences;
    private final McpChangeRepository changes;
    private final ApplicationService applications;
    private final ApplicationRepository applicationRows;
    private final ClarificationService clarifications;
    private final SupplementRepository supplements;
    private final int dailyWriteLimit;

    public McpWriteService(AnalysisService analyses, ExperienceRepository experiences, McpChangeRepository changes,
            ApplicationService applications, ApplicationRepository applicationRows,
            ClarificationService clarifications, SupplementRepository supplements,
            @Value("${app.mcp.daily-write-limit}") int dailyWriteLimit) {
        this.analyses = analyses;
        this.experiences = experiences;
        this.changes = changes;
        this.applications = applications;
        this.applicationRows = applicationRows;
        this.clarifications = clarifications;
        this.supplements = supplements;
        this.dailyWriteLimit = dailyWriteLimit;
    }

    public record AddedSource(long changeId, JobResponses.Submitted submitted) {
    }

    public record UpdatedExperience(long changeId, long experienceId, SourceStatus sourceStatus) {
    }

    public record CreatedApplication(long changeId, QuestionResponses.ApplicationSubmitted submitted) {
    }

    public record AnsweredClarification(long changeId, long supplementId, long experienceId) {
    }

    @Transactional
    public AddedSource addSource(long userId, String client, String text) {
        JobResponses.Submitted submitted = analyses.submit(userId, client, text);
        McpChange change = changes.save(McpChange.addSource(userId, client, submitted.sourceId(), submitted.jobId()));
        return new AddedSource(change.getId(), submitted);
    }

    /** 웹과 같은 코드로 지원을 저장한다 — 검사 · 한도가 같고, 걸리면 변경 기록도 남지 않는다. */
    @Transactional
    public CreatedApplication createApplication(long userId, String client, ApplicationService.ApplicationRequest request) {
        QuestionResponses.ApplicationSubmitted submitted = applications.submit(userId, request);
        String organization = applicationRows.findByIdAndUserId(submitted.applicationId(), userId).orElseThrow()
                .getOrganization();
        McpChange change = changes.save(McpChange.createApplication(userId, client, submitted.applicationId(),
                organization, submitted.questionIds().size()));
        return new CreatedApplication(change.getId(), submitted);
    }

    /** 본문만 바꾸고 NEEDS_REVIEW 로 낮춘다. 행을 잠그고 비교해, 읽은 뒤 바뀐 본문을 덮어쓰지 않는다. */
    @Transactional
    public UpdatedExperience updateExperience(long userId, String client, long experienceId, String expectedContent,
            String content, String reason) {
        checkWriteLimit(userId);
        Experience experience = experiences.findOwnedForUpdate(experienceId, userId)
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND", "경험 " + experienceId + " 이(가) 없습니다"));
        if (!experience.getContent().equals(expectedContent)) {
            throw new McpToolException("STALE_CONTENT", "경험 본문이 그새 바뀌었습니다 — 다시 읽고 부르세요");
        }
        McpChange change = changes.save(McpChange.updateExperience(userId, client, experience, content, reason));
        experience.rewriteByExternalAi(content, change.getId(), client);
        return new UpdatedExperience(change.getId(), experience.getId(), experience.getSourceStatus());
    }

    /** 웹과 같은 코드로 보완을 저장하고 확인 전으로 둔다 — 맞아요를 누르기 전엔 근거가 아니다(S-56). */
    @Transactional
    public AnsweredClarification answerClarification(long userId, String client, long clarificationId, String answer) {
        checkWriteLimit(userId);
        ClarificationService.Answered answered = clarifications.answer(userId, clarificationId,
                new ClarificationService.AnswerRequest(answer));
        Supplement supplement = supplements.findById(answered.supplement().id()).orElseThrow();
        Experience experience = experiences.findOwned(answered.experienceId(), userId).orElseThrow();
        McpChange change = changes.save(McpChange.answerClarification(userId, client, experience, supplement));
        supplement.markFromExternalAi(change.getId(), client);
        return new AnsweredClarification(change.getId(), supplement.getId(), experience.getId());
    }

    // 기존 변경 기록을 센다 — 한국 날짜 기준. 동시에 부르면 몇 건 넘을 수 있지만 상한의 뜻(폭주 막기)은 그대로다
    private void checkWriteLimit(long userId) {
        OffsetDateTime today = LocalDate.now(Times.SEOUL).atStartOfDay(Times.SEOUL).toOffsetDateTime();
        if (changes.countByUserIdAndTypeInAndCreatedAtGreaterThanEqual(userId, LIMITED, today) >= dailyWriteLimit) {
            throw new McpToolException("WRITE_LIMIT_EXCEEDED",
                    "오늘 쓰기 상한(" + dailyWriteLimit + "건)을 넘었습니다. 한국 시간 자정에 다시 채워집니다");
        }
    }
}

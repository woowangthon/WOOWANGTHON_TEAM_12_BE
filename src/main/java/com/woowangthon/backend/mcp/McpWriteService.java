package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.job.AnalysisService;
import com.woowangthon.backend.job.JobResponses;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 AI 쓰기(계약 「외부 쓰기 도구」 · ADR-010) — 쓰기와 변경 기록이 한 트랜잭션이다. 사용자는 세션이 아니라 토큰으로 받는다.
 * 분석처럼 뒤에서 도는 일은 이 메서드가 돌아온 뒤(커밋 뒤) 도구가 시작한다.
 */
@Service
public class McpWriteService {

    private final AnalysisService analyses;
    private final ExperienceRepository experiences;
    private final McpChangeRepository changes;
    private final int dailyWriteLimit;

    public McpWriteService(AnalysisService analyses, ExperienceRepository experiences, McpChangeRepository changes,
            @Value("${app.mcp.daily-write-limit}") int dailyWriteLimit) {
        this.analyses = analyses;
        this.experiences = experiences;
        this.changes = changes;
        this.dailyWriteLimit = dailyWriteLimit;
    }

    public record AddedSource(long changeId, JobResponses.Submitted submitted) {
    }

    public record UpdatedExperience(long changeId, long experienceId, SourceStatus sourceStatus) {
    }

    @Transactional
    public AddedSource addSource(long userId, String client, String text) {
        JobResponses.Submitted submitted = analyses.submit(userId, client, text);
        McpChange change = changes.save(McpChange.addSource(userId, client, submitted.sourceId(), submitted.jobId()));
        return new AddedSource(change.getId(), submitted);
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

    // 기존 변경 기록을 센다 — 한국 날짜 기준. 동시에 부르면 몇 건 넘을 수 있지만 상한의 뜻(폭주 막기)은 그대로다
    private void checkWriteLimit(long userId) {
        OffsetDateTime today = LocalDate.now(Times.SEOUL).atStartOfDay(Times.SEOUL).toOffsetDateTime();
        if (changes.countByUserIdAndTypeAndCreatedAtGreaterThanEqual(userId, McpChangeType.UPDATE_EXPERIENCE, today)
                >= dailyWriteLimit) {
            throw new McpToolException("WRITE_LIMIT_EXCEEDED",
                    "오늘 쓰기 상한(" + dailyWriteLimit + "건)을 넘었습니다. 한국 시간 자정에 다시 채워집니다");
        }
    }
}

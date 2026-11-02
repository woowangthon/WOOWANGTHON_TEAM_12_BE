package com.woowangthon.backend.job;

import com.woowangthon.backend.common.ErrorResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 계약 「POST /api/sources」 · 「GET /api/jobs/{jobId}」 응답 모양. */
public final class JobResponses {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private JobResponses() {
    }

    public record Submitted(Long sourceId, Long jobId, JobStatus status) {
    }

    public record Job(
            Long jobId,
            Long sourceId,
            JobStatus status,
            List<Long> activityIds,
            ErrorResponse.Error error,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public static Job from(AnalysisJob j) {
            ErrorResponse.Error error = j.getErrorCode() == null
                    ? null
                    : new ErrorResponse.Error(j.getErrorCode(), j.getErrorMessage());
            return new Job(j.getId(), j.getSource().getId(), j.getStatus(), List.copyOf(j.getActivityIds()), error,
                    seoul(j.getCreatedAt()), seoul(j.getUpdatedAt()));
        }

        // 계약 예시처럼 "2026-11-03T14:05:00+09:00" 으로 내보낸다
        private static OffsetDateTime seoul(OffsetDateTime time) {
            return time.atZoneSameInstant(SEOUL).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
        }
    }
}

package com.woowangthon.backend.mcp;

import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.List;

/** 계약 「변경 기록」 응답 모양. */
public final class McpChangeResponses {

    private McpChangeResponses() {
    }

    public enum UndoBlockedReason {
        DECIDED,
        CHANGED_AFTER,
        ANALYZING,
        ANALYSIS_FAILED
    }

    public record Changes(List<Change> changes) {
    }

    public record Undone(Change change, int removedExperiences, int keptExperiences) {
    }

    public record Confirmed(Change change) {
    }

    /** experience · source · application · supplement 중 type 에 맞는 칸 하나만 찬다. */
    public record Change(
            Long id,
            McpChangeType type,
            String client,
            String reason,
            McpChangeStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime decidedAt,
            boolean undoable,
            UndoBlockedReason undoBlockedReason,
            ExperienceChange experience,
            SourceChange source,
            ApplicationChange application,
            SupplementChange supplement) {
    }

    public record ExperienceChange(Long id, Long activityId, String activityName, String beforeContent,
            String afterContent, SourceStatus beforeSourceStatus) {
    }

    public record SourceChange(Long id, String name, Long jobId, JobStatus jobStatus, long experienceCount) {
    }

    public record ApplicationChange(Long id, String organization, int questionCount, int analyzingCount) {
    }

    public record SupplementChange(Long id, Long experienceId, String activityName, String question, String answer) {
    }
}

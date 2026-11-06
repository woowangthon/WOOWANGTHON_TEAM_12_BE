package com.woowangthon.backend.mcp;

import com.woowangthon.backend.activity.ActivityService;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceService;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.job.AnalysisJob;
import com.woowangthon.backend.job.AnalysisJobRepository;
import com.woowangthon.backend.mcp.McpChangeResponses.UndoBlockedReason;
import com.woowangthon.backend.question.ApplicationService;
import com.woowangthon.backend.source.SourceRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변경 기록 · 되돌리기 · 맞아요(계약 「변경 기록」 · S-53 · S-54 · ADR-010). 변경 행을 먼저 잠그고, 경험을 고치는 쪽은 경험 행까지 잠근 뒤
 * 상태를 본다 — 두 번 눌러도 한 번만 일어나고, 그새 바뀐 본문을 덮어쓰지 않는다.
 */
@Service
public class McpChangeService {

    private final McpChangeRepository changes;
    private final ExperienceRepository experiences;
    private final ExperienceService experienceService;
    private final ActivityService activityService;
    private final CitationRepository citations;
    private final AnalysisJobRepository jobs;
    private final SourceRepository sources;
    private final ApplicationService applications;

    public McpChangeService(McpChangeRepository changes, ExperienceRepository experiences,
            ExperienceService experienceService, ActivityService activityService, CitationRepository citations,
            AnalysisJobRepository jobs, SourceRepository sources, ApplicationService applications) {
        this.changes = changes;
        this.experiences = experiences;
        this.experienceService = experienceService;
        this.activityService = activityService;
        this.citations = citations;
        this.jobs = jobs;
        this.sources = sources;
        this.applications = applications;
    }

    // 변경들이 가리키는 지금 값 — 목록이 한 건씩 읽지 않게 한 번에 모은다. 지워진 경험 · 지원은 그 맵에 없다
    private record Targets(Map<Long, Experience> experiences, Map<Long, AnalysisJob> jobs,
            Map<Long, String> sourceNames, Map<Long, Long> experienceCounts,
            Map<Long, ApplicationService.ApplicationState> applications) {
    }

    private record Removed(int removed, int kept) {
    }

    @Transactional(readOnly = true)
    public McpChangeResponses.Changes list(long userId) {
        List<McpChange> recent = changes.findTop100ByUserIdOrderByCreatedAtDescIdDesc(userId);
        Targets targets = targets(userId, recent);
        return new McpChangeResponses.Changes(recent.stream().map(change -> response(change, targets)).toList());
    }

    @Transactional
    public McpChangeResponses.Undone undo(long userId, long changeId) {
        McpChange change = findForUpdate(userId, changeId);
        lockTarget(userId, change);
        Targets targets = targets(userId, List.of(change));
        if (blockedReason(change, targets) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_CHANGE_NOT_UNDOABLE",
                    "변경 " + changeId + " 은(는) 되돌릴 수 없습니다");
        }
        Removed result = switch (change.getType()) {
            case UPDATE_EXPERIENCE -> undoRewrite(change, targets.experiences().get(change.getExperienceId()));
            case ADD_SOURCE -> undoSource(userId, change, targets.jobs().get(change.getJobId()));
            case CREATE_APPLICATION -> undoApplication(userId, change);
        };
        change.undo();
        return new McpChangeResponses.Undone(response(change, targets(userId, List.of(change))), result.removed(),
                result.kept());
    }

    @Transactional
    public McpChangeResponses.Confirmed confirm(long userId, long changeId) {
        McpChange change = findForUpdate(userId, changeId);
        lockTarget(userId, change);
        Targets targets = targets(userId, List.of(change));
        if (change.getType() != McpChangeType.UPDATE_EXPERIENCE || blockedReason(change, targets) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_CHANGE_NOT_CONFIRMABLE",
                    "변경 " + changeId + " 은(는) 맞아요를 누를 수 없습니다");
        }
        targets.experiences().get(change.getExperienceId()).confirmExternalAiRewrite();
        change.confirm();
        return new McpChangeResponses.Confirmed(response(change, targets));
    }

    private McpChange findForUpdate(long userId, long changeId) {
        return changes.findOwnedForUpdate(changeId, userId)
                .orElseThrow(() -> ApiException.notFound("MCP_CHANGE_NOT_FOUND", "변경 " + changeId + " 이(가) 없습니다"));
    }

    // targets 보다 먼저 부른다 — 이미 읽은 엔티티는 잠가도 값이 새로 읽히지 않는다
    private void lockTarget(long userId, McpChange change) {
        if (change.getType() == McpChangeType.UPDATE_EXPERIENCE) {
            experiences.findOwnedForUpdate(change.getExperienceId(), userId);
        } else if (change.getType() == McpChangeType.CREATE_APPLICATION) {
            applications.lock(userId, change.getApplicationId());
        }
    }

    private Removed undoRewrite(McpChange change, Experience experience) {
        // A → B 로 두 번 고친 경험의 B 를 무르면 A 는 아직 정하지 않은 변경이다
        McpChange pending = changes.findFirstByExperienceIdAndStatusAndIdLessThanOrderByIdDesc(change.getExperienceId(),
                        McpChangeStatus.APPLIED, change.getId())
                .filter(earlier -> Objects.equals(earlier.getAfterContent(), change.getBeforeContent()))
                .orElse(null);
        experience.undoExternalAiRewrite(change.getBeforeContent(), change.getBeforeSourceStatus(),
                pending == null ? null : pending.getId(), pending == null ? null : pending.getClient());
        return new Removed(0, 0);
    }

    // 사용자가 손댄 경험은 남긴다. 원본은 남긴 경험의 근거일 수 있어 지우지 않는다
    private Removed undoSource(long userId, McpChange change, AnalysisJob job) {
        List<Experience> cited = citations.findExperiencesOfSource(change.getSourceId());
        List<Long> createdActivities = List.copyOf(job.getActivityIds());
        Set<Long> withChange = Set.copyOf(
                changes.findExperienceIdsWithChange(cited.stream().map(Experience::getId).toList()));

        int removed = 0;
        for (Experience experience : cited) {
            if (!touchedByUser(experience, createdActivities, withChange)) {
                experienceService.delete(experience.getId());
                removed++;
            }
        }
        createdActivities.forEach(activityId -> activityService.removeIfBare(userId, activityId));
        return new Removed(removed, cited.size() - removed);
    }

    // 쓴 분석 횟수는 돌아오지 않는다(S-55)
    private Removed undoApplication(long userId, McpChange change) {
        applications.remove(userId, change.getApplicationId());
        return new Removed(0, 0);
    }

    private static boolean touchedByUser(Experience experience, List<Long> createdActivities, Set<Long> withChange) {
        return experience.getSourceStatus() == SourceStatus.USER_ENTERED
                || !experience.getSupplements().isEmpty()
                || !createdActivities.contains(experience.getActivity().getId())
                || withChange.contains(experience.getId());
    }

    private static UndoBlockedReason blockedReason(McpChange change, Targets targets) {
        if (change.getStatus() != McpChangeStatus.APPLIED) {
            return UndoBlockedReason.DECIDED;
        }
        return switch (change.getType()) {
            case UPDATE_EXPERIENCE -> changedAfter(change, targets.experiences().get(change.getExperienceId()))
                    ? UndoBlockedReason.CHANGED_AFTER
                    : null;
            case ADD_SOURCE -> switch (targets.jobs().get(change.getJobId()).getStatus()) {
                case QUEUED, ANALYZING -> UndoBlockedReason.ANALYZING;
                case FAILED -> UndoBlockedReason.ANALYSIS_FAILED;
                case COMPLETED -> null;
            };
            case CREATE_APPLICATION -> applicationBlocked(change, targets.applications().get(change.getApplicationId()));
        };
    }

    // 분석이 끝나도 풀리지 않는 쪽(CHANGED_AFTER)을 먼저 알린다
    private static UndoBlockedReason applicationBlocked(McpChange change, ApplicationService.ApplicationState now) {
        if (now == null || now.touched() || now.questionCount() != change.getQuestionCount()) {
            return UndoBlockedReason.CHANGED_AFTER;
        }
        return now.analyzingCount() > 0 ? UndoBlockedReason.ANALYZING : null;
    }

    private static boolean changedAfter(McpChange change, Experience experience) {
        return experience == null
                || !experience.getContent().equals(change.getAfterContent())
                || experience.getSourceStatus() != SourceStatus.NEEDS_REVIEW;
    }

    private Targets targets(long userId, List<McpChange> of) {
        List<Long> experienceIds = of.stream().map(McpChange::getExperienceId).filter(Objects::nonNull).toList();
        List<Long> jobIds = of.stream().map(McpChange::getJobId).filter(Objects::nonNull).toList();
        List<Long> sourceIds = of.stream().map(McpChange::getSourceId).filter(Objects::nonNull).toList();
        List<Long> applicationIds = of.stream().map(McpChange::getApplicationId).filter(Objects::nonNull).toList();
        return new Targets(
                experiences.findAllOwned(experienceIds, userId).stream()
                        .collect(Collectors.toMap(Experience::getId, Function.identity())),
                jobs.findAllById(jobIds).stream().collect(Collectors.toMap(AnalysisJob::getId, Function.identity())),
                sources.findNamesByIdIn(sourceIds).stream()
                        .collect(Collectors.toMap(SourceRepository.Name::getId, SourceRepository.Name::getName)),
                citations.countExperiencesBySource(sourceIds).stream()
                        .collect(Collectors.toMap(CitationRepository.ExperienceCount::getSourceId,
                                CitationRepository.ExperienceCount::getExperienceCount)),
                applications.states(userId, applicationIds));
    }

    private static McpChangeResponses.Change response(McpChange c, Targets targets) {
        UndoBlockedReason blocked = blockedReason(c, targets);
        McpChangeResponses.ExperienceChange experience = c.getType() != McpChangeType.UPDATE_EXPERIENCE ? null
                : new McpChangeResponses.ExperienceChange(c.getExperienceId(), c.getActivityId(), c.getActivityName(),
                        c.getBeforeContent(), c.getAfterContent(), c.getBeforeSourceStatus());
        McpChangeResponses.SourceChange source = c.getType() != McpChangeType.ADD_SOURCE ? null
                : new McpChangeResponses.SourceChange(c.getSourceId(), targets.sourceNames().get(c.getSourceId()),
                        c.getJobId(), targets.jobs().get(c.getJobId()).getStatus(),
                        targets.experienceCounts().getOrDefault(c.getSourceId(), 0L));
        McpChangeResponses.ApplicationChange application = c.getType() != McpChangeType.CREATE_APPLICATION ? null
                : applicationChange(c, targets.applications().get(c.getApplicationId()));
        return new McpChangeResponses.Change(c.getId(), c.getType(), c.getClient(), c.getReason(), c.getStatus(),
                Times.seoul(c.getCreatedAt()), seoulOrNull(c.getDecidedAt()), blocked == null, blocked, experience,
                source, application, null);
    }

    private static McpChangeResponses.ApplicationChange applicationChange(McpChange c,
            ApplicationService.ApplicationState now) {
        return new McpChangeResponses.ApplicationChange(c.getApplicationId(), c.getOrganization(), c.getQuestionCount(),
                now == null ? 0 : now.analyzingCount());
    }

    private static OffsetDateTime seoulOrNull(OffsetDateTime time) {
        return time == null ? null : Times.seoul(time);
    }
}

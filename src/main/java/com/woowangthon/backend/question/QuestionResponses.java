package com.woowangthon.backend.question;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** question-api.md 의 응답 모양 — 지원 넣기 · Application · 문항 넣기 · QuestionSummary · 목록 · 문항 하나. */
public final class QuestionResponses {

    private QuestionResponses() {
    }

    // 문항 넣기 · 지원에 문항 더하기의 응답 — applicationId 는 문항 더하기 계약엔 없지만 더 실어도 깨지지 않는다
    public record Submitted(Long questionId, Long applicationId, JobStatus status) {
    }

    // 지원 넣기 — questionIds 는 넣은 순서
    public record ApplicationSubmitted(Long applicationId, List<Long> questionIds) {
    }

    public record Summary(Long id, Long applicationId, String organization, String text, QuestionType questionType,
            QuestionTypeBy questionTypeBy, JobStatus status, OffsetDateTime createdAt) {

        static Summary from(Question q) {
            return new Summary(q.getId(), q.getApplication().getId(), q.getOrganization(), q.getText(),
                    q.getQuestionType(), q.getQuestionTypeBy(), q.getStatus(), Times.seoul(q.getCreatedAt()));
        }
    }

    // questions — 넣은 순서(지원서의 문항 순서)
    public record ApplicationResponse(Long id, String organization, OrganizationType organizationType, JobRole jobRole,
            List<Summary> questions, OffsetDateTime createdAt) {

        static ApplicationResponse from(Application a) {
            return new ApplicationResponse(a.getId(), a.getOrganization(), a.getOrganizationType(), a.getJobRole(),
                    a.getQuestions().stream().map(Summary::from).toList(), Times.seoul(a.getCreatedAt()));
        }
    }

    public record Applications(List<ApplicationResponse> applications) {
    }

    public record Questions(List<Summary> questions) {
    }

    public record RequirementResponse(String text, RequirementStatus status, List<Long> experienceIds) {
    }

    public record ActivityRef(Long id, String name, Period period) {

        static ActivityRef from(Activity a) {
            return new ActivityRef(a.getId(), a.getName(), a.getPeriod());
        }
    }

    public record EvidenceResponse(EvidenceType type, Long id) {
    }

    public record RecommendationResponse(
            ActivityRef activity,
            ExperienceResponse experience,
            String reason,
            String emphasis,
            List<EvidenceResponse> evidence,
            List<String> gaps) {
    }

    // supplementId — 답했으면 그 보완(experience.supplements[] 에 있다), 아직이면 null
    public record ClarificationResponse(Long id, Long experienceId, String question, Long supplementId) {
    }

    public record Detail(
            Long id,
            Long applicationId,
            String organization,
            String text,
            QuestionType questionType,
            QuestionTypeBy questionTypeBy,
            JobStatus status,
            ErrorResponse.Error error,
            List<RequirementResponse> requirements,
            List<RecommendationResponse> recommendations,
            List<ClarificationResponse> clarifications,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        /**
         * 추천 · 되묻기의 경험은 지금 상태로 싣는다(문항을 넣은 뒤 고치거나 옮겼으면 그대로). 그새 지워진 경험(existing 에 없음)은
         * 요구사항의 experienceIds · 추천 · 되묻기에서 뺀다. 추천의 evidence 에서 그새 지워진 보완도 뺀다(S-24) — 근거 줄은
         * 번호만 들고 있어 보완을 지워도 남는다(BE#37).
         */
        static Detail from(Question q, Map<Long, Experience> existing) {
            ErrorResponse.Error error = q.getErrorCode() == null
                    ? null
                    : new ErrorResponse.Error(q.getErrorCode(), q.getErrorMessage());
            List<RequirementResponse> requirements = q.getRequirements().stream()
                    .map(r -> new RequirementResponse(r.getText(), r.getStatus(),
                            r.getExperienceIds().stream().filter(existing::containsKey).toList()))
                    .toList();
            List<RecommendationResponse> recommendations = q.getRecommendations().stream()
                    .filter(r -> existing.containsKey(r.getExperienceId()))
                    .map(r -> {
                        Experience e = existing.get(r.getExperienceId());
                        return new RecommendationResponse(ActivityRef.from(e.getActivity()), ExperienceResponse.from(e),
                                r.getReason(), r.getEmphasis(),
                                r.getEvidence().stream()
                                        .filter(v -> v.getType() != EvidenceType.SUPPLEMENT || hasSupplement(e, v.getRefId()))
                                        .map(v -> new EvidenceResponse(v.getType(), v.getRefId())).toList(),
                                List.copyOf(r.getGaps()));
                    })
                    .toList();
            List<ClarificationResponse> clarifications = q.getClarifications().stream()
                    .filter(c -> existing.containsKey(c.getExperienceId()))
                    .map(c -> new ClarificationResponse(c.getId(), c.getExperienceId(), c.getText(),
                            existing.get(c.getExperienceId()).getSupplements().stream()
                                    .filter(s -> c.getId().equals(s.getClarificationId()))
                                    .map(Supplement::getId).findFirst().orElse(null)))
                    .toList();
            return new Detail(q.getId(), q.getApplication().getId(), q.getOrganization(), q.getText(), q.getQuestionType(),
                    q.getQuestionTypeBy(), q.getStatus(), error, requirements, recommendations, clarifications,
                    Times.seoul(q.getCreatedAt()), Times.seoul(q.getUpdatedAt()));
        }

        private static boolean hasSupplement(Experience e, Long supplementId) {
            return e.getSupplements().stream().anyMatch(s -> s.getId().equals(supplementId));
        }
    }
}

package com.woowangthon.backend.question;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** question-api.md 의 응답 모양 — 문항 넣기 · QuestionSummary · 목록 · 문항 하나. */
public final class QuestionResponses {

    private QuestionResponses() {
    }

    public record Submitted(Long questionId, JobStatus status) {
    }

    public record Summary(Long id, String organization, String text, JobStatus status, OffsetDateTime createdAt) {

        static Summary from(Question q) {
            return new Summary(q.getId(), q.getOrganization(), q.getText(), q.getStatus(), Times.seoul(q.getCreatedAt()));
        }
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

    // supplementId — 되묻기에 답하면 생기는 보완(BE#23). 아직 답을 받지 않으므로 늘 null
    public record ClarificationResponse(Long id, Long experienceId, String question, Long supplementId) {
    }

    public record Detail(
            Long id,
            String organization,
            String text,
            JobStatus status,
            ErrorResponse.Error error,
            List<RequirementResponse> requirements,
            List<RecommendationResponse> recommendations,
            List<ClarificationResponse> clarifications,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        /**
         * 추천 · 되묻기의 경험은 지금 상태로 싣는다(문항을 넣은 뒤 고치거나 옮겼으면 그대로). 그새 지워진 경험(existing 에 없음)은
         * 요구사항의 experienceIds · 추천 · 되묻기에서 뺀다.
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
                                r.getEvidence().stream().map(v -> new EvidenceResponse(v.getType(), v.getRefId())).toList(),
                                List.copyOf(r.getGaps()));
                    })
                    .toList();
            List<ClarificationResponse> clarifications = q.getClarifications().stream()
                    .filter(c -> existing.containsKey(c.getExperienceId()))
                    .map(c -> new ClarificationResponse(c.getId(), c.getExperienceId(), c.getText(), null))
                    .toList();
            return new Detail(q.getId(), q.getOrganization(), q.getText(), q.getStatus(), error, requirements,
                    recommendations, clarifications, Times.seoul(q.getCreatedAt()), Times.seoul(q.getUpdatedAt()));
        }
    }
}

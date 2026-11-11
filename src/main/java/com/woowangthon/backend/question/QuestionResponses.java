package com.woowangthon.backend.question;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    // 지원 넣기 — questionIds 는 넣은 순서. postingMatch — 공고 기준 경험 찾기를 시작했다(응답엔 싣지 않는다 — 부르는 쪽이 작업을 돌린다)
    public record ApplicationSubmitted(Long applicationId, List<Long> questionIds, @JsonIgnore boolean postingMatch) {
    }

    public record Summary(Long id, Long applicationId, String organization, String text, QuestionType questionType,
            QuestionTypeBy questionTypeBy, Integer targetLength, JobStatus status, OffsetDateTime createdAt) {

        static Summary from(Question q) {
            return new Summary(q.getId(), q.getApplication().getId(), q.getOrganization(), q.getText(),
                    q.getQuestionType(), q.getQuestionTypeBy(), q.getTargetLength(), q.getStatus(),
                    Times.seoul(q.getCreatedAt()));
        }
    }

    // questions — 넣은 순서(지원서의 문항 순서)
    public record ApplicationResponse(Long id, String organization, OrganizationType organizationType, JobRole jobRole,
            String postingUrl, ApplicationStatus applicationStatus, String submittedMonth, List<Summary> questions,
            OffsetDateTime createdAt, List<PostingLineResponse> postingLines, PostingMatchView postingMatch) {

        static ApplicationResponse from(Application a, PostingMatchView postingMatch) {
            return new ApplicationResponse(a.getId(), a.getOrganization(), a.getOrganizationType(), a.getJobRole(),
                    a.getPostingUrl(), a.getApplicationStatus(), a.getSubmittedMonth(),
                    a.getQuestions().stream().map(Summary::from).toList(), Times.seoul(a.getCreatedAt()),
                    a.getPostingLines().stream().map(PostingLineResponse::from).toList(), postingMatch);
        }
    }

    // 공고 기준 경험 찾기(S-93) — ANALYZING · FAILED 면 lines · clarifications 는 [], FAILED 면 error
    public record PostingMatchView(JobStatus status, ErrorResponse.Error error, List<PostingLineMatch> lines,
            List<ClarificationResponse> clarifications) {
    }

    public record PostingLineMatch(Long postingLineId, RequirementStatus status, List<Long> experienceIds) {
    }

    public record PostingLineResponse(Long id, PostingSection section, String text, Ksa ksa, ReadFrom readFrom) {
        static PostingLineResponse from(ApplicationPostingLine line) {
            return new PostingLineResponse(line.getId(), line.getSection(), line.getText(), line.getKsa(), line.getReadFrom());
        }
    }

    public record Applications(List<ApplicationResponse> applications) {
    }

    public record Questions(List<Summary> questions) {
    }

    public record RequirementResponse(String text, RequirementStatus status, List<Long> experienceIds) {
    }

    public record ActivityRef(Long id, String name, Period period) {

        public static ActivityRef from(Activity a) {
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

    // supplementId — 답했으면 그 보완(experience.supplements[] 에 있다), 아직이면 null.
    // postingLineId — 공고 기준 경험 찾기(S-93)의 되묻기면 그 줄, 문항 되묻기면 null
    public record ClarificationResponse(Long id, Long experienceId, String question, Long supplementId,
            Long postingLineId) {
    }

    public record Detail(
            Long id,
            Long applicationId,
            String organization,
            String text,
            QuestionType questionType,
            QuestionTypeBy questionTypeBy,
            Integer targetLength,
            JobStatus status,
            ErrorResponse.Error error,
            List<RequirementResponse> requirements,
            List<RecommendationResponse> recommendations,
            List<ClarificationResponse> clarifications,
            DraftService.DraftView draft,
            AnswerService.AnswerView answer,
            AnswerDraftService.AnswerDraftView answerDraft,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        /**
         * 추천 · 되묻기의 경험은 지금 상태로 싣는다(문항을 넣은 뒤 고치거나 옮겼으면 그대로). 그새 지워진 경험(existing 에 없음)은
         * 요구사항의 experienceIds · 추천 · 되묻기에서 뺀다. 추천의 evidence 에서 그새 지워진 보완도 뺀다(S-24) — 근거 줄은
         * 번호만 들고 있어 보완을 지워도 남는다(BE#37).
         */
        static Detail from(Question q, Map<Long, Experience> existing, DraftService.DraftView draft,
                AnswerService.AnswerView answer, AnswerDraftService.AnswerDraftView answerDraft) {
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
                                    .map(Supplement::getId).findFirst().orElse(null), null))
                    .toList();
            return new Detail(q.getId(), q.getApplication().getId(), q.getOrganization(), q.getText(), q.getQuestionType(),
                    q.getQuestionTypeBy(), q.getTargetLength(), q.getStatus(), error, requirements, recommendations, clarifications, draft, answer,
                    answerDraft, Times.seoul(q.getCreatedAt()), Times.seoul(q.getUpdatedAt()));
        }

        private static boolean hasSupplement(Experience e, Long supplementId) {
            return e.getSupplements().stream().anyMatch(s -> s.getId().equals(supplementId));
        }
    }
}

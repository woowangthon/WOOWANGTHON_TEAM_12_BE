package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.question.Question;
import java.time.OffsetDateTime;
import com.woowangthon.backend.source.SectionKind;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceSection;
import java.util.List;
import java.util.Optional;

/** 계약의 Experience 모양. */
public record ExperienceResponse(
        Long id,
        Long activityId,
        String content,
        List<Competency> competencies,
        CompetenciesBy competenciesBy,
        Actor actor,
        SourceStatus sourceStatus,
        boolean shared,
        List<CitationResponse> citations,
        List<SupplementResponse> supplements,
        McpChangeRef mcpChange,
        Origin origin) {

    /** 외부 AI 가 고쳐 사용자가 아직 정하지 않은 경험만(S-53), 그 밖은 null. AI 서버 요청에는 싣지 않는다. */
    public record McpChangeRef(Long changeId, String client) {

        static McpChangeRef of(Experience e) {
            return e.getMcpChangeId() == null ? null : new McpChangeRef(e.getMcpChangeId(), e.getMcpClient());
        }
    }

    /**
     * 경험이 온 곳(S-48) — 확정 답의 후보를 저장한 경험만, 그 밖은 null. 지원처 이름 · 문항 글은 응답 때 지금 값으로.
     */
    public record Origin(ExperienceOrigin type, Long questionId, Long applicationId, String organization,
            String questionText) {

        static Origin of(Question q) {
            return q == null ? null
                    : new Origin(ExperienceOrigin.ANSWER, q.getId(), q.getApplication().getId(), q.getOrganization(),
                            q.getText());
        }
    }

    public record CitationResponse(
            Long id,
            Long sourceId,
            String sourceName,
            String quote,
            Boolean matched,
            Location location) {

        static CitationResponse from(Citation c) {
            Location location = c.getStartLine() == null ? null
                    : new Location(c.getStartLine(), c.getEndLine(),
                            Section.of(c.getSource(), c.getStartLine(), c.getEndLine()));
            return new CitationResponse(c.getId(), c.getSource().getId(), c.getSource().getName(), c.getQuote(),
                    c.getMatched(), location);
        }
    }

    public record Location(Integer startLine, Integer endLine, Section section) {
    }

    /**
     * 근거가 원본의 몇 쪽 · 몇 번째 슬라이드인지(S-39) — from 은 startLine 이, to 는 endLine 이 든 구간 번호.
     * 원본에 구간이 없거나(.md · .txt · 붙여넣기) 두 줄 중 하나라도 어느 구간에도 들지 않으면 null.
     */
    public record Section(SectionKind kind, int from, int to) {

        static Section of(Source source, Integer startLine, Integer endLine) {
            if (startLine == null || endLine == null) {
                return null;
            }
            Optional<SourceSection> start = source.sectionOf(startLine);
            Optional<SourceSection> end = source.sectionOf(endLine);
            if (start.isEmpty() || end.isEmpty()) {
                return null;
            }
            return new Section(start.get().getKind(), start.get().getNumber(), end.get().getNumber());
        }
    }

    /** 계약의 Supplement 모양 — 늘 「내가 입력」이라 출처 칸이 없다. */
    public record SupplementResponse(
            Long id,
            String question,
            String answer,
            Long questionId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public static SupplementResponse from(Supplement s) {
            return new SupplementResponse(s.getId(), s.getQuestion(), s.getAnswer(), s.getQuestionId(),
                    Times.seoul(s.getCreatedAt()), Times.seoul(s.getUpdatedAt()));
        }
    }

    public static ExperienceResponse from(Experience e) {
        return new ExperienceResponse(e.getId(), e.getActivity().getId(), e.getContent(), e.getCompetencies(),
                e.getCompetenciesBy(), e.getActor(), e.getSourceStatus(), e.isShared(), e.getCitations().stream().map(CitationResponse::from).toList(),
                e.getSupplements().stream().map(SupplementResponse::from).toList(), McpChangeRef.of(e),
                Origin.of(e.getOriginQuestion()));
    }
}

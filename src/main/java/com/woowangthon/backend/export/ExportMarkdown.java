package com.woowangthon.backend.export;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.job.JobStatus;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationStatus;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import com.woowangthon.backend.question.QuestionResponses;
import com.woowangthon.backend.question.QuestionType;
import com.woowangthon.backend.question.QuestionTypeBy;
import com.woowangthon.backend.question.RequirementStatus;
import com.woowangthon.backend.source.SectionKind;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 내보내기 Markdown 한 장을 쓴다(ETC docs/contracts/export-api.md 「문서 모양」 · S-33). DB 를 읽지 않고 받은 값만 글로 옮긴다 —
 * 같은 값이면 exportedAt 말고는 같은 글이다. 사용자 글은 고치지 않고 Markdown 구조가 깨지지 않게만 다듬는다.
 */
final class ExportMarkdown {

    static final DateTimeFormatter EXPORTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final String READING_GUIDE = """
            > **읽는 법** — 이 파일은 사용자가 차곡에 쌓은 경험을 내보낸 것이다.
            > - `[원문 확인]` 원본 자료에서 확인한 경험 · `[내가 입력]` 사용자가 직접 넣거나 고친 것 · `[확인 필요]` 근거를 원본에서 찾지 못해 사용자가 아직 확인하지 않은 것 — 확인된 사실처럼 쓰지 않는다.
            > - 「근거」의 인용은 원본 발췌문이고, 「보완」은 사용자가 질문에 직접 한 답이다.
            > - `(AI 해석)` 이 붙은 것(역량 · 문항 유형 · 추천 이유 · 요구사항 판정)은 AI 가 붙인 해석이지 원문 사실이 아니다.
            > - 「팀」은 팀이 한 일, 「알 수 없음」은 누가 했는지 모르는 일이다 — 사용자 혼자 한 일처럼 바꿔 쓰지 않는다.
            > - 여기 없는 경험 · 수치는 「없다」가 아니라 「쌓인 자료에 없다」이다. 지어내지 않는다.
            """;

    private ExportMarkdown() {
    }

    // --- 세 대상 ---

    static String all(List<Activity> timeline, OffsetDateTime exportedAt) {
        StringBuilder out = head("all", null, "내 경험", exportedAt);
        if (timeline.isEmpty()) {
            return out.append("아직 쌓인 활동이 없어요\n").toString();
        }
        int experienceCount = timeline.stream().mapToInt(a -> a.getExperiences().size()).sum();
        out.append("활동 ").append(timeline.size()).append("개 · 경험 ").append(experienceCount).append("개\n");
        for (Activity activity : timeline) {
            out.append("\n---\n\n## ").append(heading(activity.getName())).append("\n\n");
            activityBody(out, activity);
        }
        return out.toString();
    }

    static String activity(Activity activity, OffsetDateTime exportedAt) {
        StringBuilder out = head("activity", activity.getId(), heading(activity.getName()), exportedAt);
        activityBody(out, activity);
        return out.toString();
    }

    static String application(Application application, List<QuestionResponses.Detail> questions,
            OffsetDateTime exportedAt) {
        String title = application.getOrganization() == null || application.getOrganization().isBlank()
                ? "이름 없는 지원"
                : heading(application.getOrganization()) + " — 지원";
        StringBuilder out = head("application", application.getId(), title, exportedAt);

        List<String> facts = new ArrayList<>();
        if (application.getOrganizationType() != null) {
            facts.add("종류: " + label(application.getOrganizationType()));
        }
        if (application.getJobRole() != null) {
            facts.add("직무: " + label(application.getJobRole()));
        }
        if (application.getApplicationStatus() != null) {
            facts.add("상태: " + label(application.getApplicationStatus()));
        }
        if (application.getSubmittedMonth() != null) {
            facts.add("낸 달: " + application.getSubmittedMonth());
        }
        boolean wroteFacts = false;
        if (!facts.isEmpty()) {
            out.append(String.join(" · ", facts)).append('\n');
            wroteFacts = true;
        }
        if (application.getPostingUrl() != null) {
            out.append("공고: ").append(application.getPostingUrl()).append('\n');
            wroteFacts = true;
        }

        for (int i = 0; i < questions.size(); i++) {
            if (wroteFacts || i > 0) {
                out.append('\n');
            }
            question(out, i + 1, questions.get(i));
        }
        return out.toString();
    }

    // --- 공통 머리 ---

    private static StringBuilder head(String scope, Long id, String title, OffsetDateTime exportedAt) {
        return new StringBuilder()
                .append("---\n")
                .append("exportedFrom: 차곡 — 내가 우왕이 될 상인가\n")
                .append("scope: ").append(scope).append('\n')
                .append("id: ").append(id == null ? "null" : id).append('\n')
                .append("exportedAt: ").append(EXPORTED_AT.format(exportedAt)).append('\n')
                .append("---\n\n")
                .append("# ").append(title).append("\n\n")
                .append(READING_GUIDE)
                .append('\n');
    }

    // --- 활동 블록 ---

    private static void activityBody(StringBuilder out, Activity activity) {
        List<ExperienceResponse> experiences = activity.getExperiences().stream().map(ExperienceResponse::from).toList();
        out.append(period(activity.getPeriod())).append(" · 경험 ").append(experiences.size()).append("개\n");
        if (activity.getNote() != null) {
            out.append("\n계기 [내가 입력]: ").append(activity.getNote()).append('\n');
        }
        if (experiences.isEmpty()) {
            out.append("\n아직 경험이 없어요\n");
            return;
        }
        for (ExperienceResponse experience : experiences) {
            out.append('\n');
            experience(out, "###", experience, null, null);
        }
    }

    static String period(Period period) {
        if (period == null || period.start() == null) {
            return "기간 모름";
        }
        if (period.ongoing()) {
            return period.start() + " ~ 진행 중";
        }
        return period.end() == null ? period.start() : period.start() + " ~ " + period.end();
    }

    /**
     * 경험 하나 — 제목 · 상태 줄 · (출처) · (추천 이유) · 근거 · 보완. activityName · reason 은 지원의 「고른 경험」에서만 온다.
     */
    private static void experience(StringBuilder out, String level, ExperienceResponse e, String activityName,
            String reason) {
        String content = e.content() == null ? "" : e.content().strip();
        int newline = content.indexOf('\n');
        String title = newline < 0 ? content : content.substring(0, newline).strip();
        out.append(level).append(' ').append(heading(title)).append("\n\n");
        if (newline >= 0) {
            String rest = content.substring(newline + 1).strip();
            if (!rest.isEmpty()) {
                out.append(rest).append("\n\n");
            }
        }

        if (activityName != null) {
            out.append("활동: ").append(activityName).append(" · ");
        }
        out.append(statusLine(e)).append('\n');
        if (e.origin() != null) {
            List<String> parts = new ArrayList<>(List.of("출처: 내가 쓴 지원서"));
            if (e.origin().organization() != null) {
                parts.add(e.origin().organization());
            }
            if (e.origin().questionText() != null) {
                parts.add(e.origin().questionText());
            }
            out.append(String.join(" · ", parts)).append('\n');
        }
        if (reason != null) {
            out.append("\n추천 이유 (AI 해석): ").append(reason).append('\n');
        }

        if (!e.citations().isEmpty()) {
            out.append("\n근거\n");
            for (int i = 0; i < e.citations().size(); i++) {
                if (i > 0) {
                    out.append('\n');
                }
                ExperienceResponse.CitationResponse c = e.citations().get(i);
                out.append(quote(c.quote())).append(">\n> — ").append(citationSource(c)).append('\n');
            }
        }

        // 확인 전 「Claude 를 거친 답」은 싣지 않는다(S-56)
        List<ExperienceResponse.SupplementResponse> supplements = e.supplements().stream()
                .filter(s -> s.mcpChange() == null)
                .toList();
        if (!supplements.isEmpty()) {
            out.append("\n보완 [내가 입력]\n");
            for (ExperienceResponse.SupplementResponse s : supplements) {
                out.append("- Q. ").append(indent(s.question())).append('\n');
                out.append("  A. ").append(indent(s.answer())).append('\n');
            }
        }
    }

    private static String statusLine(ExperienceResponse e) {
        List<String> parts = new ArrayList<>();
        parts.add("[" + label(e.sourceStatus()) + "]");
        parts.add("수행: " + label(e.actor()));
        if (e.competencies() != null && !e.competencies().isEmpty()) {
            String prefix = e.competenciesBy() == CompetenciesBy.AI ? "역량 (AI 해석): " : "역량: ";
            parts.add(prefix + e.competencies().stream().map(ExportMarkdown::label).collect(Collectors.joining(", ")));
        }
        if (e.mcpChange() != null) {
            parts.add("Claude 가 고침(확인 전)");
        }
        return String.join(" · ", parts);
    }

    private static String citationSource(ExperienceResponse.CitationResponse c) {
        String name = c.sourceName();
        if (Boolean.FALSE.equals(c.matched())) {
            return name + " · 원본에서 찾지 못한 발췌";
        }
        ExperienceResponse.Location location = c.location();
        if (location == null) {
            return name;
        }
        if (location.section() != null) {
            ExperienceResponse.Section s = location.section();
            String range = s.from() == s.to() ? String.valueOf(s.from()) : s.from() + "–" + s.to();
            return name + " · " + (s.kind() == SectionKind.SLIDE ? "슬라이드 " + range : range + "쪽");
        }
        if (location.startLine() == null) {
            return name;
        }
        Integer end = location.endLine();
        String lines = end == null || end.equals(location.startLine())
                ? String.valueOf(location.startLine())
                : location.startLine() + "–" + end;
        return name + " · " + lines + "줄";
    }

    // --- 지원 하나 ---

    private static void question(StringBuilder out, int number, QuestionResponses.Detail q) {
        out.append("## 문항 ").append(number).append(". ").append(heading(q.text())).append("\n\n");

        List<String> type = new ArrayList<>();
        if (q.questionType() != null) {
            type.add((q.questionTypeBy() == QuestionTypeBy.AI ? "유형 (AI 해석): " : "유형: ") + label(q.questionType()));
        }
        if (q.targetLength() != null) {
            type.add("목표 " + q.targetLength() + "자");
        }
        if (!type.isEmpty()) {
            out.append(String.join(" · ", type)).append("\n\n");
        }

        if (q.answer() != null && q.answer().text() != null) {
            out.append("### 내가 쓴 답 — 확정 ").append(q.answer().confirmedAt().toLocalDate()).append("\n\n")
                    .append(q.answer().text().strip()).append('\n');
        } else if (q.answerDraft() != null && q.answerDraft().text() != null && !q.answerDraft().text().isBlank()) {
            out.append("### 쓰는 중인 글 — 확정 전\n\n").append(q.answerDraft().text().strip()).append('\n');
        } else {
            out.append("### 내가 쓴 답\n\n아직 쓴 답이 없어요\n");
        }

        // 추천을 대신 채우지 않는다(대회 규칙 2)
        if (q.status() == JobStatus.FAILED) {
            out.append("\n추천을 찾지 못했어요(").append(q.error() == null ? "UNKNOWN" : q.error().code()).append(")\n");
            return;
        }
        if (q.status() != JobStatus.COMPLETED) {
            out.append("\n추천을 찾는 중이에요 — 끝난 뒤 다시 내보내 주세요\n");
            return;
        }

        if (!q.requirements().isEmpty()) {
            out.append("\n### 이 문항이 보려는 것 (AI 해석)\n\n");
            for (QuestionResponses.RequirementResponse r : q.requirements()) {
                out.append("- ").append(indent(r.text())).append(" — ").append(label(r.status())).append('\n');
            }
        }
        if (!q.recommendations().isEmpty()) {
            out.append("\n### 고른 경험\n");
            for (QuestionResponses.RecommendationResponse r : q.recommendations()) {
                out.append('\n');
                experience(out, "####", r.experience(), r.activity().name(), r.reason());
            }
        }
    }

    // --- 글 다듬기 ---

    /** 인용 블록 — 줄마다 앞에 「> 」(빈 줄은 「>」). 끝에 줄바꿈. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : (text == null ? "" : text.strip()).split("\n", -1)) {
            String trimmed = line.stripTrailing();
            out.append(trimmed.isEmpty() ? ">" : "> " + trimmed).append('\n');
        }
        return out.toString();
    }

    /** 제목으로 쓰는 글 — 줄바꿈은 공백으로, 맨 앞의 # 은 \#. */
    static String heading(String text) {
        String line = text == null ? "" : text.strip().replaceAll("\\s*\\R\\s*", " ");
        return line.startsWith("#") ? "\\" + line : line;
    }

    // 목록 항목 안의 여러 줄 — 다음 줄을 들여 써서 같은 항목에 남긴다
    private static String indent(String text) {
        return (text == null ? "" : text.strip()).replace("\n", "\n    ");
    }

    // --- 값 → 글자(README 「값 목록」의 뜻) ---

    static String label(SourceStatus value) {
        return switch (value) {
            case SOURCE_CONFIRMED -> "원문 확인";
            case USER_ENTERED -> "내가 입력";
            case NEEDS_REVIEW -> "확인 필요";
        };
    }

    static String label(Actor value) {
        return switch (value) {
            case ME -> "나";
            case TEAM -> "팀";
            case UNKNOWN -> "알 수 없음";
        };
    }

    static String label(Competency value) {
        return switch (value) {
            case IMPLEMENTATION -> "기술 구현";
            case PROBLEM_SOLVING -> "문제 해결";
            case PLANNING -> "기획";
            case ANALYSIS -> "분석";
            case OPERATION -> "운영";
            case COLLABORATION -> "협업";
            case COMMUNICATION -> "소통";
            case LEADERSHIP -> "리더십";
            case INITIATIVE -> "주도성";
            case RESPONSIBILITY -> "책임감";
            case PERSEVERANCE -> "끈기";
            case LEARNING -> "학습";
            case PRESENTATION -> "발표";
        };
    }

    static String label(RequirementStatus value) {
        return switch (value) {
            case SUPPORTED -> "근거 있음";
            case PARTIAL -> "일부";
            case NOT_FOUND -> "자료에서 확인 안 됨";
        };
    }

    static String label(QuestionType value) {
        return switch (value) {
            case MOTIVATION -> "지원 동기 · 포부 · 목표";
            case SELF_INTRO -> "자기소개 · 성격 · 가치관";
            case STRENGTH_WEAKNESS -> "장단점";
            case ACHIEVEMENT -> "성취 · 몰입 · 열정";
            case PROBLEM_SOLVING -> "문제 해결 · 기술적 어려움";
            case COLLABORATION -> "협업 · 소통 · 팀워크";
            case CONFLICT -> "갈등 · 의견 조율";
            case FAILURE -> "실패 · 극복 · 아쉬움";
            case GROWTH -> "성장 과정 · 배운 점 · 피드백";
            case IDEA -> "아이디어 · 기획 제안";
            case JOB_SKILL -> "직무 역량 · 직무 준비 · 다룰 수 있는 기술";
            case OTHER -> "기타";
        };
    }

    static String label(OrganizationType value) {
        return switch (value) {
            case CLUB -> "동아리 · 학회";
            case EXTRACURRICULAR -> "교내 비교과 프로그램";
            case EXTERNAL -> "대외활동 · 서포터즈 · 공모전";
            case BOOTCAMP -> "부트캠프 · 교육 과정";
            case INTERN -> "인턴";
            case JOB -> "채용(신입 · 경력)";
            case OTHER -> "기타";
        };
    }

    static String label(JobRole value) {
        return switch (value) {
            case BACKEND -> "백엔드 · 서버";
            case FRONTEND -> "프론트엔드 · 웹";
            case MOBILE -> "모바일 앱";
            case AI_DATA -> "AI · 데이터";
            case INFRA -> "인프라 · 클라우드 · DevOps · 보안";
            case DESIGN -> "디자인";
            case PLANNING -> "기획 · PM";
            case MARKETING -> "마케팅 · 홍보 · 콘텐츠";
            case BUSINESS -> "경영 · 사무 · 영업";
            case OTHER -> "기타";
        };
    }

    static String label(ApplicationStatus value) {
        return switch (value) {
            case WRITING -> "작성 중";
            case SUBMITTED -> "냈음";
            case PASSED -> "합격";
            case NOT_PASSED -> "불합격";
            case UNKNOWN -> "모름";
        };
    }
}

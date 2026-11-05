package com.woowangthon.backend.job;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.ExtractRequest;
import com.woowangthon.backend.ai.ExtractResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.source.SectionKind;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceSection;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자료 넣기 → 분석 작업 → 결과 저장 (ETC docs/contracts/experience-api.md 「자료 넣기」 · 「작업 상태」 · 「다시 시도」).
 * AI 호출 자체는 트랜잭션 밖(ExtractionRunner)에서 한다.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private static final DateTimeFormatter PASTED_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SourceRepository sources;
    private final AnalysisJobRepository jobs;
    private final ActivityRepository activities;
    private final ExperienceRepository experiences;
    private final CitationRepository citations;
    private final UsageLimiter limiter;
    private final int maxTextLength;

    public AnalysisService(SourceRepository sources, AnalysisJobRepository jobs, ActivityRepository activities,
            ExperienceRepository experiences, CitationRepository citations, UsageLimiter limiter,
            @Value("${app.source.max-text-length}") int maxTextLength) {
        this.sources = sources;
        this.jobs = jobs;
        this.activities = activities;
        this.experiences = experiences;
        this.citations = citations;
        this.limiter = limiter;
        this.maxTextLength = maxTextLength;
    }

    public record SourceRequest(String kind, String name, String text, List<SectionRequest> sections) {
    }

    public record SectionRequest(String kind, Integer number, Integer startLine, Integer endLine) {
    }

    /** 원본을 저장하고 분석 작업을 만든다. 분석은 이 트랜잭션이 끝난 뒤 따로 시작한다. */
    @Transactional
    public JobResponses.Submitted submit(SourceRequest request) {
        SourceKind kind = parseKind(request.kind());
        String name = kind == SourceKind.FILE ? fileName(request.name()) : pastedName();
        String text = checkText(request.text());
        List<SourceSection> sections = checkSections(kind == SourceKind.FILE ? sectionKind(name) : null,
                request.sections(), text);
        limiter.consume(CurrentUser.id());

        Source source = sources.save(new Source(CurrentUser.id(), kind, name, text, sections));
        AnalysisJob job = jobs.save(new AnalysisJob(CurrentUser.id(), source));
        return new JobResponses.Submitted(source.getId(), job.getId(), job.getStatus());
    }

    @Transactional(readOnly = true)
    public JobResponses.Job get(long jobId) {
        return JobResponses.Job.from(jobs.findByIdAndUserId(jobId, CurrentUser.id())
                .orElseThrow(() -> notFound(jobId)));
    }

    /** 실패한 작업만 다시 돌린다 — 같은 작업 ID 를 그대로 쓴다. */
    @Transactional
    public JobResponses.Job retry(long jobId) {
        AnalysisJob job = jobs.findForUpdate(jobId, CurrentUser.id()).orElseThrow(() -> notFound(jobId));
        if (job.getStatus() != JobStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_FAILED",
                    "작업 " + jobId + " 은(는) 실패 상태가 아닙니다(" + job.getStatus() + ")");
        }
        limiter.consume(job.getUserId());
        job.restart();
        jobs.flush();
        return JobResponses.Job.from(job);
    }

    @Transactional(readOnly = true)
    public ExtractRequest extractRequest(long jobId) {
        Source source = jobs.findById(jobId).orElseThrow().getSource();
        return new ExtractRequest(source.getName(), source.getText());
    }

    /**
     * AI 응답을 활동 · 경험 · 근거로 저장한다 — 출처 상태 · matched 는 AI 가 준 그대로.
     * 계약을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고 AiCallException(AI_FAILED)을 던진다.
     */
    @Transactional
    public List<Long> complete(long jobId, ExtractResponse result) {
        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        Source source = job.getSource();
        if (result.activities() == null) {
            throw AiCallException.invalidResponse("activities 가 없습니다");
        }

        List<Long> activityIds = new ArrayList<>();
        for (ExtractResponse.ActivityResult a : result.activities()) {
            Activity activity = activities.save(
                    new Activity(job.getUserId(), required(a.name(), "activities[].name"), period(a.period())));
            activityIds.add(activity.getId());

            List<ExtractResponse.ExperienceResult> extracted = a.experiences() == null ? List.of() : a.experiences();
            for (int position = 0; position < extracted.size(); position++) {
                saveExperience(activity, position, extracted.get(position), source);
            }
        }
        job.complete(activityIds);
        return activityIds;
    }

    @Transactional
    public void fail(long jobId, String code, String message) {
        jobs.findById(jobId).orElseThrow().fail(code, message);
    }

    private void saveExperience(Activity activity, int position, ExtractResponse.ExperienceResult e, Source source) {
        List<ExtractResponse.CitationResult> found = e.citations() == null ? List.of() : e.citations();
        SourceStatus status = sourceStatus(e.sourceStatus(), found);

        Experience experience = experiences.save(new Experience(activity, required(e.content(), "experiences[].content"),
                competencies(e.competencies()), CompetenciesBy.AI, actor(e.actor()), status, position));
        for (ExtractResponse.CitationResult c : found) {
            ExtractResponse.LocationResult location = c.location();
            citations.save(new Citation(experience, source, required(c.quote(), "citations[].quote"), c.matched(),
                    location == null ? null : location.startLine(),
                    location == null ? null : location.endLine()));
        }
    }

    // ai-api.md 「응답 규칙」: 근거가 1개 이상이고 전부 matched: true 일 때만 SOURCE_CONFIRMED.
    // 확인하지 않은 것을 확인했다고 저장하지 않는다(S-04)
    private static SourceStatus sourceStatus(String value, List<ExtractResponse.CitationResult> found) {
        if ("NEEDS_REVIEW".equals(value)) {
            return SourceStatus.NEEDS_REVIEW;
        }
        if (!"SOURCE_CONFIRMED".equals(value)) {
            throw AiCallException.invalidResponse("sourceStatus 는 SOURCE_CONFIRMED · NEEDS_REVIEW 중 하나여야 합니다: " + value);
        }
        boolean allMatched = !found.isEmpty() && found.stream().allMatch(c -> Boolean.TRUE.equals(c.matched()));
        if (!allMatched) {
            throw AiCallException.invalidResponse("SOURCE_CONFIRMED 인데 원본에서 찾지 못한(또는 대조하지 않은) 근거가 있습니다");
        }
        return SourceStatus.SOURCE_CONFIRMED;
    }

    // AI 가 준 순서 그대로. 응답에 없으면 [](AI 쪽 변경과 머지 순서 상관없게).
    // 목록 밖 값 · 중복 · 3개 넘는 것은 그 값만 버리고 로그를 남긴다 — 역량은 해석이라 경험 전체를 버리지 않는다
    private static List<Competency> competencies(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<Competency> kept = new LinkedHashSet<>();
        for (String value : values) {
            Optional<Competency> c = Competency.parse(value);
            if (c.isEmpty()) {
                log.warn("AI 응답의 competencies 에서 목록 밖 값을 버렸습니다: {}", value);
            } else if (kept.size() >= Competency.MAX_PER_EXPERIENCE) {
                log.warn("AI 응답의 competencies 가 {}개를 넘어 뒤의 값을 버렸습니다: {}", Competency.MAX_PER_EXPERIENCE, value);
            } else if (!kept.add(c.get())) {
                log.warn("AI 응답의 competencies 에서 중복 값을 버렸습니다: {}", value);
            }
        }
        return List.copyOf(kept);
    }

    private static Actor actor(String value) {
        for (Actor actor : Actor.values()) {
            if (actor.name().equals(value)) {
                return actor;
            }
        }
        throw AiCallException.invalidResponse("actor 는 ME · TEAM · UNKNOWN 중 하나여야 합니다: " + value);
    }

    private static Period period(ExtractResponse.PeriodResult p) {
        if (p == null) {
            return new Period(null, null, false);
        }
        try {
            return Period.validated(p.start(), p.end(), Boolean.TRUE.equals(p.ongoing()));
        } catch (ApiException e) {
            throw AiCallException.invalidResponse("period — " + e.getMessage());
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AiCallException.invalidResponse(field + " 가 비었습니다");
        }
        return value;
    }

    private static SourceKind parseKind(String value) {
        for (SourceKind kind : SourceKind.values()) {
            if (kind.name().equals(value)) {
                return kind;
            }
        }
        throw ApiException.invalidRequest("kind 는 TEXT · FILE 중 하나여야 합니다");
    }

    private static String fileName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.invalidRequest("FILE 은 name(파일 이름)이 필요합니다");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".txt") && sectionKind(name) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                    ".md · .txt · .pdf · .pptx 파일만 넣을 수 있습니다");
        }
        return name;
    }

    // .pdf 면 쪽, .pptx 면 슬라이드 — 그 밖(.md · .txt)은 구간이 없다
    private static SectionKind sectionKind(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return SectionKind.PAGE;
        }
        if (lower.endsWith(".pptx")) {
            return SectionKind.SLIDE;
        }
        return null;
    }

    /**
     * 계약 「자료 넣기」 sections — .pdf · .pptx 면 1개 이상 필수, 그 밖엔 null · 빈 배열만.
     * 줄은 text 를 \n 으로 나눠 1부터 센다. number · 줄 둘 다 오름차순이고 구간끼리 겹치지 않는다.
     */
    private static List<SourceSection> checkSections(SectionKind expected, List<SectionRequest> sections, String text) {
        if (expected == null) {
            if (sections != null && !sections.isEmpty()) {
                throw invalidSections(".md · .txt · 붙여넣은 텍스트에는 sections 를 보내지 않습니다");
            }
            return List.of();
        }
        if (sections == null || sections.isEmpty()) {
            throw invalidSections(".pdf · .pptx 는 sections 가 1개 이상 필요합니다");
        }
        int lineCount = text.split("\n", -1).length;
        List<SourceSection> checked = new ArrayList<>();
        SourceSection previous = null;
        for (int i = 0; i < sections.size(); i++) {
            SectionRequest s = sections.get(i);
            String at = "sections[" + i + "]";
            if (s == null || s.number() == null || s.startLine() == null || s.endLine() == null) {
                throw invalidSections(at + " 에 kind · number · startLine · endLine 이 모두 있어야 합니다");
            }
            if (!expected.name().equals(s.kind())) {
                throw invalidSections(at + ".kind 는 이 파일에서 " + expected + " 여야 합니다: " + s.kind());
            }
            if (s.number() < 1) {
                throw invalidSections(at + ".number 는 1 이상이어야 합니다");
            }
            if (s.startLine() < 1 || s.startLine() > s.endLine() || s.endLine() > lineCount) {
                throw invalidSections(at + " 의 줄 번호가 text(" + lineCount + "줄)를 벗어나거나 startLine > endLine 입니다");
            }
            if (previous != null
                    && (s.number() <= previous.getNumber() || s.startLine() <= previous.getEndLine())) {
                throw invalidSections(at + " 이(가) 앞 구간과 겹치거나 순서가 틀렸습니다");
            }
            previous = new SourceSection(expected, s.number(), s.startLine(), s.endLine());
            checked.add(previous);
        }
        return checked;
    }

    private static ApiException invalidSections(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SECTIONS", message);
    }

    // 계약: TEXT 면 BE 가 「붙여넣은 텍스트 2026-11-03 14:05」처럼 이름을 붙인다
    private static String pastedName() {
        return "붙여넣은 텍스트 " + ZonedDateTime.now(JobResponses.SEOUL).format(PASTED_NAME);
    }

    // 한도는 Java String.length()(UTF-16) 기준 — AI 서버(코드 포인트 수)와 FE(JS length)보다 크지 않다
    private String checkText(String text) {
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > maxTextLength) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "텍스트가 " + maxTextLength + "자를 넘습니다");
        }
        return text;
    }

    private static ApiException notFound(long jobId) {
        return ApiException.notFound("JOB_NOT_FOUND", "작업 " + jobId + " 이(가) 없습니다");
    }
}

package com.woowangthon.backend.job;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityCategory;
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
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
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

    // ai-api.md 「POST /extract」 existingActivities 최대 개수
    static final int MAX_EXISTING_ACTIVITIES = 300;

    // experience-api.md 「POST /api/sources/batch」 sources 최대 개수
    static final int MAX_BATCH_SOURCES = 10;

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

    public record SourceRequest(String kind, String name, String text, List<SectionRequest> sections,
            Long activityId) {
    }

    public record BatchRequest(List<SourceRequest> sources) {
    }

    public record SectionRequest(String kind, Integer number, Integer startLine, Integer endLine) {
    }

    // 검사를 통과한 자료 한 건 — 아직 저장하지 않았다
    private record CheckedSource(SourceKind kind, String name, String text, List<SourceSection> sections) {
    }

    /** 원본을 저장하고 분석 작업을 만든다. 분석은 이 트랜잭션이 끝난 뒤 따로 시작한다. */
    @Transactional
    public JobResponses.Submitted submit(SourceRequest request) {
        CheckedSource checked = check(request);
        long userId = CurrentUser.id();
        Long target = request.activityId();
        if (target != null && activities.findByIdAndUserId(target, userId).isEmpty()) {
            throw ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + target + " 이(가) 없습니다");
        }
        limiter.consume(userId);

        Source source = save(userId, checked);
        return saved(target == null ? new AnalysisJob(userId, source) : AnalysisJob.forActivity(userId, source, target));
    }

    /**
     * 자료 여러 개를 한 번에 넣는다(S-59). 전부 검사하고 횟수를 한꺼번에 센 뒤에야 저장해서, 하나라도 틀리면 아무것도 남지 않는다.
     * 1번만 ANALYZING 이고 나머지는 앞 작업이 끝나기를 기다린다. 분석은 이 트랜잭션이 끝난 뒤 따로 시작한다.
     */
    @Transactional
    public JobResponses.Batch submitBatch(BatchRequest request) {
        List<SourceRequest> requested = request.sources();
        if (requested == null || requested.isEmpty() || requested.size() > MAX_BATCH_SOURCES) {
            throw ApiException.invalidRequest("sources 는 1 ~ " + MAX_BATCH_SOURCES + "개여야 합니다");
        }
        List<CheckedSource> checked = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            checked.add(checkElement(i + 1, requested.get(i)));
        }
        long userId = CurrentUser.id();
        limiter.consume(userId, checked.size());

        List<JobResponses.Submitted> items = new ArrayList<>();
        for (CheckedSource c : checked) {
            Source source = save(userId, c);
            items.add(saved(items.isEmpty() ? new AnalysisJob(userId, source) : AnalysisJob.queued(userId, source)));
        }
        return new JobResponses.Batch(items);
    }

    // 원소 오류는 코드 그대로, message 에 몇째(1부터) · 파일 이름을 담는다
    private CheckedSource checkElement(int number, SourceRequest element) {
        if (element == null) {
            throw ApiException.invalidRequest(number + "번째 자료: 비었습니다");
        }
        try {
            if (element.activityId() != null) {
                throw ApiException.invalidRequest("activityId 는 묶음에서 받지 않습니다");
            }
            return check(element);
        } catch (ApiException e) {
            String label = element.name() == null || element.name().isBlank() ? "" : "(" + element.name() + ")";
            throw new ApiException(e.status(), e.code(), number + "번째 자료" + label + ": " + e.getMessage());
        }
    }

    private CheckedSource check(SourceRequest request) {
        SourceKind kind = parseKind(request.kind());
        String name = kind == SourceKind.FILE ? fileName(request.name()) : pastedName();
        String text = checkText(request.text());
        List<SourceSection> sections = checkSections(kind == SourceKind.FILE ? sectionKind(name) : null,
                request.sections(), text);
        return new CheckedSource(kind, name, text, sections);
    }

    /**
     * 외부 AI 가 보낸 글(S-52) — 세션이 없어 사용자를 토큰에서 받는다. 글자 수 · 하루 한도 · 저장은 웹과 같은 코드를 탄다.
     * 분석은 이 트랜잭션이 끝난 뒤 따로 시작한다.
     */
    @Transactional
    public JobResponses.Submitted submit(long userId, String client, String text) {
        String name = client + " 에서 보냄 — " + ZonedDateTime.now(JobResponses.SEOUL).format(PASTED_NAME);
        CheckedSource checked = new CheckedSource(SourceKind.MCP, name, checkText(text), List.of());
        limiter.consume(userId);
        return saved(new AnalysisJob(userId, save(userId, checked)));
    }

    private Source save(long userId, CheckedSource c) {
        return sources.save(new Source(userId, c.kind(), c.name(), c.text(), c.sections()));
    }

    private JobResponses.Submitted saved(AnalysisJob job) {
        jobs.save(job);
        return new JobResponses.Submitted(job.getSource().getId(), job.getId(), job.getStatus());
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

    /**
     * 부를 때마다 그때의 기존 활동 목록을 새로 만든다(다시 시도 포함) — 그래서 묶음의 앞 파일로 생긴 활동도 들어간다(S-59).
     * 실패한 작업은 활동을 남기지 않으므로 이 작업이 만든 활동은 목록에 없다.
     * 자료 더하기(S-60)의 대상은 300개 한도와 상관없이 싣고, 그새 사라졌으면 대상 없이 보낸다.
     */
    @Transactional(readOnly = true)
    public ExtractRequest extractRequest(long jobId) {
        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        Source source = job.getSource();
        List<Activity> existing = new ArrayList<>(
                activities.findRecent(job.getUserId(), Limit.of(MAX_EXISTING_ACTIVITIES)));
        Activity target = target(job.getTargetActivityId(), job.getUserId());
        if (target != null && !existing.contains(target)) {
            existing.add(target);
        }
        return new ExtractRequest(source.getName(), source.getText(),
                existing.stream()
                        .map(a -> new ExtractRequest.ExistingActivity(a.getId(), a.getName(), a.getPeriod()))
                        .toList(),
                target == null ? null : target.getId());
    }

    private Activity target(Long targetActivityId, long userId) {
        return targetActivityId == null ? null : activities.findByIdAndUserId(targetActivityId, userId).orElse(null);
    }

    /**
     * AI 응답을 활동 · 경험 · 근거로 저장한다 — 출처 상태 · matched 는 AI 가 준 그대로.
     * 계약을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고 AiCallException(AI_FAILED)을 던진다.
     * 자료 더하기(S-60)면 대상과 같다고 한 활동은 새로 만들지 않고 대상에 바로 붙인다. 대상이 그새 사라졌으면 보통 자료 넣기처럼 끝낸다.
     */
    @Transactional
    public List<Long> complete(long jobId, ExtractRequest request, ExtractResponse result) {
        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        Source source = job.getSource();
        if (result.activities() == null) {
            throw AiCallException.invalidResponse("activities 가 없습니다");
        }
        Set<Long> sent = new HashSet<>();
        request.existingActivities().forEach(e -> sent.add(e.id()));

        Activity target = target(request.targetActivityId(), job.getUserId());
        boolean matched = false;
        List<Long> activityIds = new ArrayList<>();
        for (ExtractResponse.ActivityResult a : result.activities()) {
            Long sameAs = sameAs(a.sameAsActivityId(), sent, job.getUserId());
            String name = required(a.name(), "activities[].name");
            Period period = period(a.period());
            if (target != null && target.getId().equals(sameAs)) {
                attach(target, a, source);
                matched = true;
                continue;
            }
            Activity activity = activities.save(new Activity(job.getUserId(), name, period));
            activityIds.add(activity.getId());
            activity.suggestMergeWith(sameAs);
            activity.categorizeByAi(category(a.category()));

            List<ExtractResponse.ExperienceResult> extracted = a.experiences() == null ? List.of() : a.experiences();
            for (int position = 0; position < extracted.size(); position++) {
                saveExperience(activity, position, extracted.get(position), source);
            }
        }
        job.complete(activityIds, job.getTargetActivityId() == null ? null : matched);
        return activityIds;
    }

    // 결과는 합치기(S-13)와 같다 — 대상의 이름 · 기간 · 계기는 그대로, 경험은 뒤에 AI 가 준 순서로, 미분류면 분류를 가져온다
    private void attach(Activity target, ExtractResponse.ActivityResult a, Source source) {
        target.categorizeByAiIfNone(category(a.category()));
        int position = experiences.findLastPosition(target.getId()) + 1;
        List<ExtractResponse.ExperienceResult> extracted = a.experiences() == null ? List.of() : a.experiences();
        for (ExtractResponse.ExperienceResult e : extracted) {
            saveExperience(target, position++, e, source);
        }
    }

    /** 끝난 작업의 사용자에게 돌아가는 분석이 없으면 다음 QUEUED 를 시작한다(S-59). 돌릴 작업 ID 를 돌려준다. */
    @Transactional
    public Optional<Long> startNextAfter(long finishedJobId) {
        return startNext(jobs.findById(finishedJobId).orElseThrow().getUserId());
    }

    @Transactional
    public Optional<Long> startNext(long userId) {
        return jobs.startNextQueued(userId);
    }

    /** 서버가 꺼지며 끊긴 작업을 실패로 돌린다. 몇 개인지 돌려준다. */
    @Transactional
    public int failInterrupted() {
        List<AnalysisJob> interrupted = jobs.findByStatus(JobStatus.ANALYZING);
        interrupted.forEach(job -> job.fail("INTERNAL_ERROR", "서버가 다시 시작되어 분석이 끊겼습니다"));
        return interrupted.size();
    }

    @Transactional(readOnly = true)
    public List<Long> usersWithQueued() {
        return jobs.findUsersWithQueued();
    }

    // 같은 활동 제안(S-44). 칸이 없으면 null(AI 쪽 변경과 머지 순서 상관없게).
    // 보낸 목록 밖 id 는 계약 위반 — 몰래 null 로 바꾸지 않고 실패. 보낸 뒤 합쳐져 사라졌으면 null(가리킬 활동이 없다)
    private Long sameAs(Long id, Set<Long> sent, long userId) {
        if (id == null) {
            return null;
        }
        if (!sent.contains(id)) {
            throw AiCallException.invalidResponse("sameAsActivityId 가 보낸 existingActivities 에 없습니다: " + id);
        }
        return activities.findByIdAndUserId(id, userId).isPresent() ? id : null;
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

    // 활동 분류(S-57) — AI 해석. 칸이 없거나 null 이면 미분류(AI 쪽 머지 전에도 돈다). 목록 밖 값은 계약 위반 — 실패
    private static ActivityCategory category(String value) {
        if (value == null) {
            return null;
        }
        for (ActivityCategory category : ActivityCategory.values()) {
            if (category.name().equals(value)) {
                return category;
            }
        }
        throw AiCallException.invalidResponse("activities[].category 는 PROJECT · STUDY · CLUB 중 하나여야 합니다: " + value);
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
        // MCP 는 BE 만 붙인다 — 받지 않는다
        for (SourceKind kind : List.of(SourceKind.TEXT, SourceKind.FILE)) {
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

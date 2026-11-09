package com.woowangthon.backend.guideline;

import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.GuidelinesRequest;
import com.woowangthon.backend.ai.GuidelinesResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.entrance.EntranceService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ETC docs/contracts/guideline-api.md — 지침 정리(저장 안 함) · 저장 · 고치기 · 켜고 끄기 · 지우기(SPEC S-49).
 * 글자 수는 String.length()(UTF-16) 기준 — FE(JS length)와 같은 숫자. 정리의 AI 호출은 트랜잭션 밖에서 한다.
 * 저장 · 켜기는 사용자 행을 잠가, 동시에 눌러도 켠 것 10개 · 전체 50개를 넘지 않는다.
 */
@Service
public class GuidelineService {

    private static final Logger log = LoggerFactory.getLogger(GuidelineService.class);

    static final int MAX_TEXT_LENGTH = 200;
    static final int MAX_SOURCE_LENGTH = 100;
    static final int MAX_ENABLED = 10;
    static final int MAX_SAVED = 50;
    static final int MAX_PER_SAVE = 10;
    static final int MAX_EXTRACT_TEXT_LENGTH = 20_000;

    // ai-api.md 「POST /guidelines」 응답 규칙
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_CANDIDATE_LENGTH = 100;

    private final GuidelineRepository guidelines;
    private final AppUserRepository users;
    private final AiClient ai;
    private final UsageLimiter limiter;
    private final TransactionTemplate tx;

    public GuidelineService(GuidelineRepository guidelines, AppUserRepository users, AiClient ai, UsageLimiter limiter,
            TransactionTemplate tx) {
        this.guidelines = guidelines;
        this.users = users;
        this.ai = ai;
        this.limiter = limiter;
        this.tx = tx;
    }

    public record GuidelineView(Long id, String text, String source, boolean enabled, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static GuidelineView from(Guideline g) {
            return new GuidelineView(g.getId(), g.getText(), g.getSource(), g.isEnabled(), Times.seoul(g.getCreatedAt()),
                    Times.seoul(g.getUpdatedAt()));
        }
    }

    public record GuidelineList(List<GuidelineView> guidelines) {
    }

    public record ExtractRequest(String text, String source) {
    }

    public record Candidate(String text) {
    }

    /** model 은 FE 에 넘기지 않는다. 저장하지 않는다 — 사용자가 고른 줄만 POST /api/guidelines 로 넣는다. */
    public record ExtractResult(String source, List<Candidate> candidates) {
    }

    public record SaveRequest(List<Line> guidelines) {
    }

    public record Line(String text, String source) {
    }

    @Transactional(readOnly = true)
    public GuidelineList list() {
        return new GuidelineList(guidelines.findByUserIdOrderByIdAsc(CurrentUser.id()).stream()
                .map(GuidelineView::from).toList());
    }

    /** 실패해도 센다 — 한도를 먼저 커밋하고 AI 를 부른다. 정해진 지침으로 채우지 않는다(대회 규칙 2). */
    public ExtractResult extract(ExtractRequest request) {
        String text = request.text();
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > MAX_EXTRACT_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "텍스트가 " + MAX_EXTRACT_TEXT_LENGTH + "자를 넘습니다");
        }
        String source = optionalLine("source", request.source(), MAX_SOURCE_LENGTH);

        long userId = CurrentUser.id();
        tx.executeWithoutResult(status -> limiter.consume(userId));
        try {
            GuidelinesResponse response = ai.guidelines(new GuidelinesRequest(text));
            List<Candidate> candidates = candidates(response);
            log.info("지침 정리 — 후보 {}개, 모델 {}", candidates.size(), response.model());
            return new ExtractResult(source, candidates);
        } catch (AiCallException e) {
            log.warn("지침 정리 실패 — {} {}", e.code(), e.getMessage());
            throw EntranceService.toApi(e);
        }
    }

    // ai-api.md 응답 규칙 — 어긴 값을 잘라 내거나 건너뛰어 넘기지 않는다
    private static List<Candidate> candidates(GuidelinesResponse response) {
        List<GuidelinesResponse.Line> found = response.guidelines();
        if (found == null) {
            throw AiCallException.invalidResponse("guidelines 가 없습니다");
        }
        if (found.size() > MAX_CANDIDATES) {
            throw AiCallException.invalidResponse("guidelines 는 " + MAX_CANDIDATES + "개까지입니다");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            GuidelinesResponse.Line line = found.get(i);
            if (line == null || !isLine(line.text(), MAX_CANDIDATE_LENGTH)) {
                throw AiCallException.invalidResponse(
                        "guidelines[" + i + "].text 는 줄바꿈 없는 1 ~ " + MAX_CANDIDATE_LENGTH + "자여야 합니다");
            }
            candidates.add(new Candidate(line.text()));
        }
        return candidates;
    }

    /** 켠 지침이 이미 10개면 남는 것은 꺼진 채로 — 앞에서부터 켠다. 50개를 넘으면 하나도 저장하지 않는다. */
    @Transactional
    public GuidelineList save(SaveRequest request) {
        List<Line> lines = request.guidelines();
        if (lines == null || lines.isEmpty() || lines.size() > MAX_PER_SAVE) {
            throw ApiException.invalidRequest("guidelines 는 1 ~ " + MAX_PER_SAVE + "개여야 합니다");
        }
        List<Line> checked = new ArrayList<>();
        for (Line line : lines) {
            if (line == null) {
                throw ApiException.invalidRequest("guidelines 에 빈 값이 있습니다");
            }
            checked.add(new Line(checkLine("text", line.text(), MAX_TEXT_LENGTH),
                    optionalLine("source", line.source(), MAX_SOURCE_LENGTH)));
        }

        long userId = CurrentUser.id();
        users.lockById(userId);
        if (guidelines.countByUserId(userId) + lines.size() > MAX_SAVED) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_GUIDELINES", "지침은 " + MAX_SAVED + "개까지 저장할 수 있습니다");
        }
        long enabled = guidelines.countByUserIdAndEnabledTrue(userId);
        List<Guideline> created = new ArrayList<>();
        for (Line line : checked) {
            boolean on = enabled < MAX_ENABLED;
            created.add(new Guideline(userId, line.text(), line.source(), on));
            enabled += on ? 1 : 0;
        }
        return new GuidelineList(guidelines.saveAllAndFlush(created).stream().map(GuidelineView::from).toList());
    }

    /** 이미 켜진 지침을 다시 켜는 건 세지 않는다. 이미 만든 초안의 지침은 바뀌지 않는다 — 만들 때의 글이 초안에 있다. */
    @Transactional
    public GuidelineView update(long id, Map<String, Object> raw) {
        PatchBody body = new PatchBody(raw);
        var text = body.string("text");
        var source = body.string("source");
        var enabled = body.bool("enabled");
        if (text.isEmpty() && source.isEmpty() && enabled.isEmpty()) {
            throw ApiException.invalidRequest("바꿀 필드가 없습니다");
        }
        String newText = text.map(f -> checkLine("text", f.value(), MAX_TEXT_LENGTH)).orElse(null);
        // 출처는 null · 빈 값이면 지운다 — 그래서 보냈는지(source.isPresent)를 따로 넘긴다
        String newSource = source.map(f -> optionalLine("source", f.value(), MAX_SOURCE_LENGTH)).orElse(null);
        Boolean newEnabled = enabled.map(f -> {
            if (f.value() == null) {
                throw ApiException.invalidRequest("enabled 는 true · false 여야 합니다");
            }
            return f.value();
        }).orElse(null);

        long userId = CurrentUser.id();
        users.lockById(userId);
        Guideline guideline = find(id, userId);
        if (Boolean.TRUE.equals(newEnabled) && !guideline.isEnabled()
                && guidelines.countByUserIdAndEnabledTrue(userId) >= MAX_ENABLED) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_ENABLED", "지침은 " + MAX_ENABLED + "개까지 켤 수 있습니다");
        }
        guideline.edit(newText, source.isPresent(), newSource, newEnabled);
        return GuidelineView.from(guidelines.saveAndFlush(guideline));
    }

    @Transactional
    public void delete(long id) {
        guidelines.delete(find(id, CurrentUser.id()));
    }

    private Guideline find(long id, long userId) {
        return guidelines.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("GUIDELINE_NOT_FOUND", "지침 " + id + " 이(가) 없습니다"));
    }

    private static String checkLine(String name, String value, int maxLength) {
        if (!isLine(value, maxLength)) {
            throw ApiException.invalidRequest(name + " 은(는) 줄바꿈 없는 1 ~ " + maxLength + "자여야 합니다");
        }
        return value;
    }

    /** 선택 칸 — 비었거나 null · 공백뿐이면 null 로 돌린다. 값이 있으면 줄바꿈 없는 1 ~ maxLength자. */
    private static String optionalLine(String name, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return checkLine(name, value, maxLength);
    }

    private static boolean isLine(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength && !value.contains("\n")
                && !value.contains("\r");
    }
}

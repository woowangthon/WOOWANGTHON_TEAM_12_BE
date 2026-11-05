package com.woowangthon.backend.entrance;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.AskRequest;
import com.woowangthon.backend.ai.AskResponse;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.ExperienceResponse;
import com.woowangthon.backend.question.Evidence;
import com.woowangthon.backend.question.QuestionResponses;
import com.woowangthon.backend.question.QuestionService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ETC docs/contracts/entrance-api.md 「POST /api/ask」 — 한 줄 질문에 쌓인 경험으로 바로 답한다(S-29, 규칙은 S-20).
 * 경험 전부를 매칭과 같은 모양으로 AI 에 넘기고, 돌아온 추천에 지금 상태의 활동 · 경험을 붙인다. 저장하지 않는다.
 * 하루 분석 한도에 1회 센다 — 경험이 0개라 AI 를 부르지 않으면 세지 않는다. AI 호출은 트랜잭션 밖에서 한다.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_RECOMMENDATIONS = 3;

    private final AiClient ai;
    private final QuestionService questions;
    private final ExperienceRepository experiences;
    private final UsageLimiter limiter;
    private final TransactionTemplate tx;

    public AskService(AiClient ai, QuestionService questions, ExperienceRepository experiences, UsageLimiter limiter,
            TransactionTemplate tx) {
        this.ai = ai;
        this.questions = questions;
        this.experiences = experiences;
        this.limiter = limiter;
        this.tx = tx;
    }

    public record Recommendation(QuestionResponses.ActivityRef activity, ExperienceResponse experience, String reason,
            List<QuestionResponses.EvidenceResponse> evidence) {
    }

    public record AskResult(List<Recommendation> recommendations) {
    }

    public AskResult ask(EntranceService.TextRequest request) {
        String text = request == null ? null : request.text();
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw ApiException.invalidRequest("text 가 " + MAX_TEXT_LENGTH + "자를 넘습니다");
        }
        long userId = CurrentUser.id();

        // 경험을 싣고, 있으면 한도를 쓴다 — 429 면 AI 를 부르지 않는다
        List<MatchRequest.ExperiencePart> parts = tx.execute(status -> {
            List<MatchRequest.ExperiencePart> found = questions.experienceParts(userId);
            if (!found.isEmpty()) {
                limiter.consume(userId);
            }
            return found;
        });
        if (parts.isEmpty()) {
            return new AskResult(List.of());
        }

        List<Picked> picked;
        try {
            AskResponse response = ai.ask(new AskRequest(text, parts));
            picked = check(response, parts);
            log.info("꺼내기 — 후보 {}개, 추천 {}개, 모델 {}", parts.size(), picked.size(), response.model());
        } catch (AiCallException e) {
            log.warn("꺼내기 실패 — {} {}", e.code(), e.getMessage());
            throw EntranceService.toApi(e);
        }
        if (picked.isEmpty()) {
            return new AskResult(List.of());
        }

        // 활동 · 경험은 지금 상태로 — AI 를 기다리는 새 지워진 경험은 뺀다(question-api Detail 과 같다)
        return tx.execute(status -> {
            Map<Long, Experience> existing = experiences
                    .findAllOwned(picked.stream().map(Picked::experienceId).toList(), userId).stream()
                    .collect(Collectors.toMap(Experience::getId, Function.identity()));
            return new AskResult(picked.stream()
                    .filter(p -> existing.containsKey(p.experienceId()))
                    .map(p -> {
                        Experience e = existing.get(p.experienceId());
                        return new Recommendation(QuestionResponses.ActivityRef.from(e.getActivity()),
                                ExperienceResponse.from(e), p.reason(),
                                p.evidence().stream()
                                        .map(v -> new QuestionResponses.EvidenceResponse(v.getType(), v.getRefId()))
                                        .toList());
                    })
                    .toList());
        });
    }

    private record Picked(Long experienceId, String reason, List<Evidence> evidence) {
    }

    /** ai-api.md 「POST /ask」 응답 규칙 — 벗어나면 고쳐 쓰지 않고 AI_FAILED(대회 규칙 2). */
    private static List<Picked> check(AskResponse response, List<MatchRequest.ExperiencePart> sent) {
        if (response.recommendations() == null) {
            throw AiCallException.invalidResponse("recommendations 가 없습니다");
        }
        if (response.recommendations().size() > MAX_RECOMMENDATIONS) {
            throw AiCallException.invalidResponse("recommendations 는 " + MAX_RECOMMENDATIONS + "개까지입니다");
        }
        Map<Long, MatchRequest.ExperiencePart> candidates = sent.stream()
                .collect(Collectors.toMap(MatchRequest.ExperiencePart::id, Function.identity()));
        Set<Long> seen = new HashSet<>();
        List<Picked> picked = new ArrayList<>();
        for (AskResponse.Recommendation r : response.recommendations()) {
            MatchRequest.ExperiencePart experience = r.experienceId() == null ? null : candidates.get(r.experienceId());
            if (experience == null) {
                throw AiCallException.invalidResponse(
                        "recommendations[].experienceId " + r.experienceId() + " 은(는) 보낸 경험이 아닙니다");
            }
            if (!seen.add(r.experienceId())) {
                throw AiCallException.invalidResponse("recommendations 에 같은 경험이 두 번 있습니다: " + r.experienceId());
            }
            if (r.reason() == null || r.reason().isBlank()) {
                throw AiCallException.invalidResponse("recommendations[].reason 이 비었습니다");
            }
            picked.add(new Picked(r.experienceId(), r.reason(), QuestionService.evidence(r.evidence(), experience)));
        }
        return picked;
    }
}

package com.woowangthon.backend.entrance;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.ParseApplicationRequest;
import com.woowangthon.backend.ai.ParseApplicationResponse;
import com.woowangthon.backend.ai.RouteRequest;
import com.woowangthon.backend.ai.RouteResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ETC docs/contracts/entrance-api.md — 홈 입력 가르기(S-28 · ADR-007) · 지원 나누기(S-30). AI 를 그 자리에서 부르고 저장하지 않는다.
 * 하루 분석 한도에 세지 않는다 — 뒤따르는 자료 넣기 · 지원 넣기가 센다.
 * AI 가 실패하면 오류로 돌려준다 — 정해진 kind 로 대신 채우지 않는다(대회 규칙 2).
 */
@Service
public class EntranceService {

    /** ai-api.md 「POST /route」 — AI 에는 앞 4000자(코드 포인트)만 보낸다. */
    static final int ROUTE_TEXT_CODE_POINTS = 4000;

    /** entrance-api.md 「parse-application」 — 자료 넣기(50000자)보다 짧다. */
    static final int PARSE_TEXT_MAX_LENGTH = 20_000;
    static final int MAX_ORGANIZATION_LENGTH = 100;
    static final int MAX_QUESTIONS = 10;
    static final int MAX_QUESTION_LENGTH = 5_000;

    private final AiClient ai;
    private final int maxTextLength;

    public EntranceService(AiClient ai, @Value("${app.source.max-text-length}") int maxTextLength) {
        this.ai = ai;
        this.maxTextLength = maxTextLength;
    }

    public record TextRequest(String text) {
    }

    public record RouteResult(EntranceKind kind, String reason) {
    }

    /** model 은 FE 에 넘기지 않는다. 저장하지 않는다 — 사용자가 고쳐 POST /api/applications 로 넣는다. */
    public record ParseApplicationResult(String organization, OrganizationType organizationType, JobRole jobRole,
            List<String> questions) {
    }

    public RouteResult route(TextRequest request) {
        String text = checkedText(request, maxTextLength);
        int totalLength = text.codePointCount(0, text.length());
        String head = totalLength <= ROUTE_TEXT_CODE_POINTS
                ? text
                : text.substring(0, text.offsetByCodePoints(0, ROUTE_TEXT_CODE_POINTS));

        RouteResponse response;
        try {
            response = ai.route(new RouteRequest(head, totalLength));
        } catch (AiCallException e) {
            throw toApi(e);
        }
        if (response.kind() == null || response.reason() == null || response.reason().isBlank()) {
            throw toApi(AiCallException.invalidResponse("kind · reason 이 비었습니다"));
        }
        return new RouteResult(response.kind(), response.reason());
    }

    public ParseApplicationResult parseApplication(TextRequest request) {
        String text = checkedText(request, PARSE_TEXT_MAX_LENGTH);
        try {
            ParseApplicationResponse response = ai.parseApplication(new ParseApplicationRequest(text));
            validate(response);
            return new ParseApplicationResult(response.organization(), response.organizationType(),
                    response.jobRole(), response.questions());
        } catch (AiCallException e) {
            throw toApi(e);
        }
    }

    // 한도는 자료 넣기와 같다 — String.length()(UTF-16) 기준(AnalysisService.checkText)
    private static String checkedText(TextRequest request, int maxLength) {
        String text = request == null ? null : request.text();
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > maxLength) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "텍스트가 " + maxLength + "자를 넘습니다");
        }
        return text;
    }

    // ai-api.md 응답 규칙 — 어긴 값을 잘라 내거나 null 로 바꿔 넘기지 않는다
    private static void validate(ParseApplicationResponse response) {
        String organization = response.organization();
        if (organization != null && (organization.isBlank() || organization.length() > MAX_ORGANIZATION_LENGTH)) {
            throw AiCallException.invalidResponse("organization 은 1 ~ " + MAX_ORGANIZATION_LENGTH + "자여야 합니다");
        }
        List<String> questions = response.questions();
        if (questions == null) {
            throw AiCallException.invalidResponse("questions 가 없습니다");
        }
        if (questions.size() > MAX_QUESTIONS) {
            throw AiCallException.invalidResponse("questions 는 " + MAX_QUESTIONS + "개까지입니다");
        }
        for (String question : questions) {
            if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
                throw AiCallException.invalidResponse("questions[] 는 1 ~ " + MAX_QUESTION_LENGTH + "자여야 합니다");
            }
        }
        if (new HashSet<>(questions).size() != questions.size()) {
            throw AiCallException.invalidResponse("questions 에 같은 문항이 두 번 있습니다");
        }
    }

    // entrance-api.md: 시간 초과는 504 AI_TIMEOUT, 그 밖(AI 오류 · 연결 실패 · 응답 규칙 위반)은 502 AI_FAILED
    public static ApiException toApi(AiCallException e) {
        if ("AI_TIMEOUT".equals(e.code())) {
            return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", e.getMessage());
        }
        String message = "AI_UNAVAILABLE".equals(e.code()) ? "AI_UNAVAILABLE: " + e.getMessage() : e.getMessage();
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", message);
    }
}

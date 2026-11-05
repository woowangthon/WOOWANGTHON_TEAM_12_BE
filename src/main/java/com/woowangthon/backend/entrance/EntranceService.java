package com.woowangthon.backend.entrance;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.RouteRequest;
import com.woowangthon.backend.ai.RouteResponse;
import com.woowangthon.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ETC docs/contracts/entrance-api.md — 홈 입력 가르기(S-28 · ADR-007). AI 를 그 자리에서 부르고 저장하지 않는다.
 * 하루 분석 한도에 세지 않는다 — 뒤따르는 자료 넣기 · 지원 넣기가 센다.
 * AI 가 실패하면 오류로 돌려준다 — 정해진 kind 로 대신 채우지 않는다(대회 규칙 2).
 */
@Service
public class EntranceService {

    /** ai-api.md 「POST /route」 — AI 에는 앞 4000자(코드 포인트)만 보낸다. */
    static final int ROUTE_TEXT_CODE_POINTS = 4000;

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

    public RouteResult route(TextRequest request) {
        String text = request == null ? null : request.text();
        // 한도는 자료 넣기와 같다 — String.length()(UTF-16) 기준(AnalysisService.checkText)
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > maxTextLength) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "텍스트가 " + maxTextLength + "자를 넘습니다");
        }
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

    // entrance-api.md: 시간 초과는 504 AI_TIMEOUT, 그 밖(AI 오류 · 연결 실패 · 응답 규칙 위반)은 502 AI_FAILED
    private static ApiException toApi(AiCallException e) {
        if ("AI_TIMEOUT".equals(e.code())) {
            return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", e.getMessage());
        }
        String message = "AI_UNAVAILABLE".equals(e.code()) ? "AI_UNAVAILABLE: " + e.getMessage() : e.getMessage();
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", message);
    }
}

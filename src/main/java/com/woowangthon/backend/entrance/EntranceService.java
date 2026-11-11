package com.woowangthon.backend.entrance;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.AiRejectedUrlException;
import com.woowangthon.backend.ai.ParseApplicationRequest;
import com.woowangthon.backend.ai.ParseApplicationResponse;
import com.woowangthon.backend.ai.RouteRequest;
import com.woowangthon.backend.ai.RouteResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.PostingUrls;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import com.woowangthon.backend.question.ReadFrom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ETC docs/contracts/entrance-api.md — 홈 입력 가르기(S-28 · ADR-007) · 지원 나누기(S-30 · 공고 링크 S-46). AI 를 그 자리에서 부르고 저장하지 않는다.
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

    /** text · url · images 중 하나만. 이미지 · 본문은 예외 메시지에도 싣지 않는다. */
    public record ParseRequest(String text, String url, List<ParseApplicationRequest.Image> images) {
        @Override
        public String toString() { return "ParseRequest[redacted]"; }
    }

    public record RouteResult(EntranceKind kind, String reason) {
    }

    /** model 은 FE 에 넘기지 않는다. 저장하지 않는다 — 사용자가 고쳐 POST /api/applications 로 넣는다. */
    public record ParseApplicationResult(String organization, OrganizationType organizationType, JobRole jobRole,
            List<String> questions, List<ParseApplicationResponse.PostingLine> postingLines, ReadFrom readFrom) {
        @Override
        public String toString() { return "ParseApplicationResult[redacted]"; }
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

    public ParseApplicationResult parseApplication(ParseRequest request) {
        String text = request == null ? null : request.text();
        String url = request == null ? null : request.url();
        List<ParseApplicationRequest.Image> images = request == null ? null : request.images();
        if ((text == null ? 0 : 1) + (url == null ? 0 : 1) + (images == null ? 0 : 1) != 1) {
            throw ApiException.invalidRequest("text · url · images 중 하나만 보내야 합니다");
        }
        ParseApplicationRequest aiRequest;
        if (images != null) {
            validateImages(images);
            aiRequest = ParseApplicationRequest.ofImages(images);
        } else if (url != null) {
            if (!PostingUrls.isValid(url)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_URL",
                        "url 은 " + PostingUrls.MAX_LENGTH + "자 이하의 http(s):// 주소여야 합니다");
            }
            aiRequest = ParseApplicationRequest.ofUrl(url);
        } else {
            aiRequest = ParseApplicationRequest.ofText(checkedText(new TextRequest(text), PARSE_TEXT_MAX_LENGTH));
        }
        try {
            ParseApplicationResponse response = ai.parseApplication(aiRequest);
            validate(response, images != null ? ReadFrom.IMAGE : url != null ? ReadFrom.URL : ReadFrom.TEXT);
            return new ParseApplicationResult(response.organization(), response.organizationType(),
                    response.jobRole(), response.questions(), response.postingLines(), response.readFrom());
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
    private static void validateImages(List<ParseApplicationRequest.Image> images) {
        if (images.isEmpty() || images.size() > 5) {
            throw invalidImage();
        }
        for (ParseApplicationRequest.Image image : images) {
            // 계약: 디코딩 전 base64 글자 수 4MB 이하. 파일·본문을 예외에 넣지 않는다.
            if (image == null || image.mediaType() == null
                    || !Set.of("image/png", "image/jpeg", "image/webp").contains(image.mediaType())
                    || image.data() == null || image.data().isBlank() || image.data().length() > 4 * 1024 * 1024) {
                throw invalidImage();
            }
            try {
                Base64.getDecoder().decode(image.data());
            } catch (IllegalArgumentException e) {
                throw invalidImage();
            }
        }
    }

    private static ApiException invalidImage() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE",
                "캡처는 png · jpeg · webp 1 ~ 5장이며 각 base64 데이터는 4MB 이하여야 합니다");
    }

    private static void validate(ParseApplicationResponse response, ReadFrom expectedFrom) {
        if (response.readFrom() != expectedFrom) {
            throw AiCallException.invalidResponse("readFrom 이 요청과 다릅니다");
        }
        if (response.postingLines() == null || response.postingLines().size() > 40) {
            throw AiCallException.invalidResponse("postingLines 는 0 ~ 40줄이어야 합니다");
        }
        Set<String> lines = new HashSet<>();
        for (ParseApplicationResponse.PostingLine line : response.postingLines()) {
            if (line == null || line.section() == null || line.text() == null || line.text().isBlank()
                    || line.text().length() > 300 || !lines.add(line.text())) {
                throw AiCallException.invalidResponse("postingLines 의 형식 · 길이 · 중복이 올바르지 않습니다");
            }
        }
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

    // entrance-api.md: 시간 초과는 504 AI_TIMEOUT, 그 밖(AI 오류 · 연결 실패 · 응답 규칙 위반)은 502 AI_FAILED.
    // 링크 · 캡처 못 읽음(422)은 AI 의 code · message 그대로 — FE 가 붙여넣기로 돌아간다.
    public static ApiException toApi(AiCallException e) {
        if (e instanceof AiRejectedUrlException) {
            return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage());
        }
        if ("AI_TIMEOUT".equals(e.code())) {
            return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", e.getMessage());
        }
        String message = "AI_UNAVAILABLE".equals(e.code()) ? "AI_UNAVAILABLE: " + e.getMessage() : e.getMessage();
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", message);
    }
}

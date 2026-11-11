package com.woowangthon.backend.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 서버(ETC docs/contracts/ai-api.md)를 부른다. 실패는 전부 AiCallException 으로 바꿔 던진다 —
 * 정해진 결과로 대신 채우지 않는다(대회 규칙 2).
 */
@Component
public class AiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z_]+)\"");
    /** ai-api.md 「POST /parse-application」 422 — 링크 · 캡처 못 읽음(S-46 · S-92). */
    private static final Set<String> PARSE_REJECTED_CODES = Set.of("URL_BLOCKED", "URL_UNREADABLE", "IMAGE_UNREADABLE");

    /** 제한 시간이 다른 두 갈래 — 작업(추출 · 매칭)과 사용자가 화면에서 기다리는 동기 호출(입구 셋, ai-api.md). */
    private record Caller(RestClient rest, long timeoutSeconds) {
    }

    private final Caller job;
    private final Caller sync;
    private final HttpClient http;
    private final URI agentUri;
    private final long agentTimeoutSeconds;
    private final JsonMapper json;

    public AiClient(@Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.timeout-seconds}") long timeoutSeconds,
            @Value("${app.ai.sync-timeout-seconds}") long syncTimeoutSeconds,
            @Value("${app.chagok.timeout-seconds}") long agentTimeoutSeconds, JsonMapper json) {
        // JDK 기본은 HTTP/2 라 평문 http 에 Upgrade: h2c 를 보내는데, uvicorn(AI 서버)은 이를 400 으로 거절한다
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.job = caller(http, baseUrl, timeoutSeconds);
        this.sync = caller(http, baseUrl, syncTimeoutSeconds);
        this.agentUri = URI.create(baseUrl.replaceAll("/+$", "") + "/agent");
        this.agentTimeoutSeconds = agentTimeoutSeconds;
        this.json = json;
    }

    private static Caller caller(HttpClient http, String baseUrl, long timeoutSeconds) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return new Caller(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), timeoutSeconds);
    }

    public ExtractResponse extract(ExtractRequest request) {
        return post(job, "/extract", request, ExtractResponse.class);
    }

    public MatchResponse match(MatchRequest request) {
        return post(job, "/match", request, MatchResponse.class);
    }

    /** 공고 기준 경험 찾기(S-93) — 뒤에서 도는 작업이라 match 와 같은 제한 시간. */
    public PostingMatchResponse postingMatch(PostingMatchRequest request) {
        return post(job, "/posting-match", request, PostingMatchResponse.class);
    }

    /** 경험 STAR 칸 나누기(S-95) — 뒤에서 도는 작업이라 match 와 같은 제한 시간. */
    public StarResponse star(StarRequest request) {
        return post(job, "/star", request, StarResponse.class);
    }

    /** 내가 쓴 답 근거 점검 — 작업(question-api 「근거 점검」). */
    public GroundCheckResponse groundCheck(GroundCheckRequest request) {
        return post(job, "/ground-check", request, GroundCheckResponse.class);
    }

    /** 고른 경험으로 답변 초안 — 작업(question-api 「답변 초안」). */
    public DraftResponse draft(DraftRequest request) {
        return post(job, "/draft", request, DraftResponse.class);
    }

    /** 확정 답에서 새 경험 후보 찾기 — 작업(question-api 「확정 답변」). */
    public NewFactsResponse newFacts(NewFactsRequest request) {
        return post(job, "/new-facts", request, NewFactsResponse.class);
    }

    /** 홈 입력 가르기 — 동기(entrance-api). */
    public RouteResponse route(RouteRequest request) {
        return post(sync, "/route", request, RouteResponse.class);
    }

    /** 한 줄 질문에 맞는 경험(꺼내기 S-29) — 동기(entrance-api). */
    public AskResponse ask(AskRequest request) {
        return post(sync, "/ask", request, AskResponse.class);
    }

    /** 문항 · 공고(또는 공고 링크 — S-46)에서 지원 칸 나누기(S-30) — 동기(entrance-api). 링크를 못 읽으면 AiRejectedUrlException. */
    public ParseApplicationResponse parseApplication(ParseApplicationRequest request) {
        return post(sync, "/parse-application", request, ParseApplicationResponse.class);
    }

    /** 활동 사이 이어짐 제안(S-35) — 동기(experience-api). */
    public LinkSuggestionResponse linkSuggestions(LinkSuggestionRequest request) {
        return post(sync, "/link-suggestions", request, LinkSuggestionResponse.class);
    }

    /** 붙여 넣은 작성법을 지침 한 줄씩으로(S-49) — 동기(guideline-api). */
    public GuidelinesResponse guidelines(GuidelinesRequest request) {
        return post(sync, "/guidelines", request, GuidelinesResponse.class);
    }

    /**
     * 차곡이 도구 루프를 시작하고 NDJSON 스트림을 연다(ai-api.md 「POST /agent」). 받는 쪽이 닫는다.
     * RestClient 는 응답을 다 읽는 것을 전제로 하므로, 읽는 도중 끊을 수 있게 HttpClient 를 바로 쓴다.
     */
    public AgentStream agent(AgentRequest request) {
        HttpRequest post = HttpRequest.newBuilder(agentUri)
                .timeout(Duration.ofSeconds(agentTimeoutSeconds))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request)))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(post, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new AiCallException("AI_UNAVAILABLE", "AI 서버가 HTTP " + response.statusCode() + " 로 답했습니다");
            }
            return new AgentStream(response.body());
        } catch (IOException e) {
            throw classify(e, agentTimeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private <T> T post(Caller caller, String uri, Object request, Class<T> type) {
        T body;
        try {
            body = caller.rest().post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw aiError(res.getStatusCode().value(), res.getBody());
                    })
                    .body(type);
        } catch (ResourceAccessException e) {
            throw classify(e, caller.timeoutSeconds());
        } catch (RestClientException e) {
            // 파서 오류에 이미지에서 읽은 본문이 섞일 수 있으므로 이 경로는 원래 예외를 노출하지 않는다.
            throw AiCallException.invalidResponse("/parse-application".equals(uri)
                    ? "JSON 을 읽지 못했습니다"
                    : "JSON 을 읽지 못했습니다 — " + e.getMessage());
        }
        if (body == null) {
            throw AiCallException.invalidResponse("본문이 비었습니다");
        }
        return body;
    }

    private static AiCallException classify(Exception e, long timeoutSeconds) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // HttpConnectTimeoutException 은 HttpTimeoutException 의 하위라 먼저 본다
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return unavailable();
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new AiCallException("AI_TIMEOUT", "AI 서버가 " + timeoutSeconds + "초 안에 답하지 않았습니다");
            }
        }
        return unavailable();
    }

    private static AiCallException unavailable() {
        return new AiCallException("AI_UNAVAILABLE", "AI 서버에 연결하지 못했습니다");
    }

    // 오류 본문 { "error": { "code": "LLM_ERROR", "message": ... } } 에서 code 를 꺼낸다. 모양이 다르면 UNKNOWN.
    // 링크 · 캡처 못 읽음 422만 message 까지 꺼내 그대로 넘긴다 — 그 밖은 AI_FAILED 로 뭉친다
    private AiCallException aiError(int status, InputStream body) {
        String text;
        try (body) {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        Matcher m = ERROR_CODE.matcher(text);
        String code = m.find() ? m.group(1) : "UNKNOWN";
        if (status == 422 && PARSE_REJECTED_CODES.contains(code)) {
            String message = rejectedMessage(text);
            if (message != null) {
                return new AiRejectedUrlException(code, message);
            }
        }
        return new AiCallException("AI_FAILED", code + " (HTTP " + status + ")");
    }

    private String rejectedMessage(String text) {
        try {
            String message = json.readTree(text).path("error").path("message").asString();
            return message == null || message.isBlank() ? null : message;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

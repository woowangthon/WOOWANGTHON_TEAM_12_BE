package com.woowangthon.backend.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/**
 * AI 서버(ETC docs/contracts/ai-api.md)를 부른다. 실패는 전부 AiCallException 으로 바꿔 던진다 —
 * 정해진 결과로 대신 채우지 않는다(대회 규칙 2).
 */
@Component
public class AiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z_]+)\"");

    private final RestClient rest;
    private final long timeoutSeconds;

    public AiClient(@Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.timeout-seconds}") long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        // JDK 기본은 HTTP/2 라 평문 http 에 Upgrade: h2c 를 보내는데, uvicorn(AI 서버)은 이를 400 으로 거절한다
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public ExtractResponse extract(ExtractRequest request) {
        return post("/extract", request, ExtractResponse.class);
    }

    public MatchResponse match(MatchRequest request) {
        return post("/match", request, MatchResponse.class);
    }

    private <T> T post(String uri, Object request, Class<T> type) {
        T body;
        try {
            body = rest.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new AiCallException("AI_FAILED",
                                aiErrorCode(res.getBody()) + " (HTTP " + res.getStatusCode().value() + ")");
                    })
                    .body(type);
        } catch (ResourceAccessException e) {
            throw classify(e);
        } catch (RestClientException e) {
            throw AiCallException.invalidResponse("JSON 을 읽지 못했습니다 — " + e.getMessage());
        }
        if (body == null) {
            throw AiCallException.invalidResponse("본문이 비었습니다");
        }
        return body;
    }

    private AiCallException classify(ResourceAccessException e) {
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

    // 오류 본문 { "error": { "code": "LLM_ERROR", ... } } 에서 code 만 꺼낸다. 모양이 다르면 UNKNOWN
    private static String aiErrorCode(InputStream body) {
        try (body) {
            Matcher m = ERROR_CODE.matcher(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "UNKNOWN";
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }
}

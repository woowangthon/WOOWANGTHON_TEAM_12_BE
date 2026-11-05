package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/entrance-api.md 「POST /api/entrance/route」 · 「…/parse-application」 과 ai-api.md 의 같은 이름.
 * AI 서버 자리에 가짜 HTTP 서버를 띄워 응답 · 오류 · 늦은 답을 돌려준다(테스트 전용 — 앱은 진짜 AI 서버만 부른다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.sync-timeout-seconds=1")
class EntranceApiTest {

    record FakeReply(int status, String body, long delayMillis) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile FakeReply reply;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        HttpHandler handler = exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            FakeReply r = reply;
            if (r.delayMillis() > 0) {
                try {
                    Thread.sleep(r.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(r.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // 제한 시간 테스트에서 BE 가 먼저 끊은 경우
            }
            exchange.close();
        };
        FAKE_AI.createContext("/route", handler);
        FAKE_AI.createContext("/parse-application", handler);
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String QUESTION = "팀 프로젝트에서 맡은 역할과, 팀과 함께 일한 방식을 적어 주세요.";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();
        reply = new FakeReply(200, """
                {"kind": "APPLICATION", "reason": "지원서가 묻는 문항 문장이다", "model": "gpt-test"}""", 0);
    }

    private ResultActions route(String json) throws Exception {
        return mvc.perform(post("/api/entrance/route").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String textBody(String text) {
        return "{\"text\": \"" + text + "\"}";
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private int usedToday() {
        return jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage where user_id = ?",
                Integer.class, TestLogin.USER_ID);
    }

    @Test
    void 가르기_결과를_바로_돌려주고_한도에_세지_않는다() throws Exception {
        int before = usedToday();

        route(textBody(QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("APPLICATION"))
                .andExpect(jsonPath("$.reason").value("지원서가 묻는 문항 문장이다"))
                .andExpect(jsonPath("$.model").doesNotExist());

        assertThat(RECEIVED).hasSize(1);
        assertThat((String) JsonPath.read(RECEIVED.getFirst(), "$.text")).isEqualTo(QUESTION);
        assertThat(JsonPath.<Integer>read(RECEIVED.getFirst(), "$.totalLength")).isEqualTo(QUESTION.length());
        assertThat(usedToday()).isEqualTo(before);
    }

    @Test
    void 긴_글은_앞_4000_코드_포인트와_전체_길이만_보낸다() throws Exception {
        // 이모지(서로게이트 쌍)를 섞어 String.length() 와 코드 포인트 수가 다르게
        String emoji = new String(Character.toChars(0x1F600));
        String text = emoji.repeat(3_000) + "가".repeat(1_500);
        reply = new FakeReply(200, "{\"kind\": \"SOURCE\", \"reason\": \"긴 회고 글이다\"}", 0);

        route(textBody(text)).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("SOURCE"));

        String sent = JsonPath.read(RECEIVED.getFirst(), "$.text");
        assertThat(sent).isEqualTo(emoji.repeat(3_000) + "가".repeat(1_000));
        assertThat(JsonPath.<Integer>read(RECEIVED.getFirst(), "$.totalLength")).isEqualTo(4_500);
    }

    @Test
    void 빈_글과_너무_긴_글은_AI_를_부르지_않는다() throws Exception {
        expectError(route(textBody("  \\n ")), 400, "EMPTY_TEXT");
        expectError(route("{}"), 400, "EMPTY_TEXT");
        expectError(route(textBody("가".repeat(50_001))), 413, "TEXT_TOO_LONG");
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void AI_오류는_502_AI_FAILED_이고_message_에_AI_code() throws Exception {
        reply = new FakeReply(502, """
                {"error": {"code": "LLM_INVALID_OUTPUT", "message": "bad"}}""", 0);

        String body = route(textBody(QUESTION)).andExpect(status().isBadGateway()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat((String) JsonPath.read(body, "$.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(body, "$.error.message")).contains("LLM_INVALID_OUTPUT");
    }

    @Test
    void 값_목록_밖의_kind_나_빈_reason_은_대신_채우지_않고_AI_FAILED() throws Exception {
        reply = new FakeReply(200, "{\"kind\": \"OTHER\", \"reason\": \"모름\"}", 0);
        expectError(route(textBody(QUESTION)), 502, "AI_FAILED");

        reply = new FakeReply(200, "{\"kind\": \"ASK\", \"reason\": \" \"}", 0);
        expectError(route(textBody(QUESTION)), 502, "AI_FAILED");
    }

    @Test
    void 제한_시간을_넘기면_504_AI_TIMEOUT() throws Exception {
        reply = new FakeReply(200, "{\"kind\": \"ASK\", \"reason\": \"늦은 답\"}", 2_000);
        expectError(route(textBody(QUESTION)), 504, "AI_TIMEOUT");
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
        expectError(anonymous.perform(post("/api/entrance/route").contentType(MediaType.APPLICATION_JSON)
                .content(textBody(QUESTION))), 401, "UNAUTHENTICATED");
        assertThat(RECEIVED).isEmpty();
    }

    static final String POSTING = "[OO 부트캠프 모집] 모집 분야: 백엔드 1. 해결한 문제를 적어 주세요. 2. 지원 동기를 적어 주세요.";

    private ResultActions parse(String json) throws Exception {
        return mvc.perform(post("/api/entrance/parse-application").contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void 지원_나누기_결과를_바로_돌려주고_저장도_한도_세기도_하지_않는다() throws Exception {
        int usedBefore = usedToday();
        int applicationsBefore = jdbc.queryForObject("select count(*) from application", Integer.class);
        reply = new FakeReply(200, """
                {"organization": "OO 부트캠프", "organizationType": "BOOTCAMP", "jobRole": "BACKEND",
                 "questions": ["해결한 문제를 적어 주세요.", "지원 동기를 적어 주세요."], "model": "gpt-test"}""", 0);

        parse(textBody(POSTING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("OO 부트캠프"))
                .andExpect(jsonPath("$.organizationType").value("BOOTCAMP"))
                .andExpect(jsonPath("$.jobRole").value("BACKEND"))
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0]").value("해결한 문제를 적어 주세요."))
                .andExpect(jsonPath("$.model").doesNotExist());

        assertThat(RECEIVED).hasSize(1);
        assertThat((String) JsonPath.read(RECEIVED.getFirst(), "$.text")).isEqualTo(POSTING);
        assertThat(usedToday()).isEqualTo(usedBefore);
        assertThat(jdbc.queryForObject("select count(*) from application", Integer.class))
                .isEqualTo(applicationsBefore);
    }

    @Test
    void 문항을_못_찾으면_빈_배열과_null_을_그대로_돌려준다() throws Exception {
        reply = new FakeReply(200, """
                {"organization": null, "organizationType": null, "jobRole": null, "questions": []}""", 0);

        parse(textBody(POSTING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value(nullValue()))
                .andExpect(jsonPath("$.organizationType").value(nullValue()))
                .andExpect(jsonPath("$.jobRole").value(nullValue()))
                .andExpect(jsonPath("$.questions").isEmpty());
    }

    @Test
    void 지원_나누기_빈_글과_2만자_넘는_글은_AI_를_부르지_않는다() throws Exception {
        expectError(parse(textBody("  \\n ")), 400, "EMPTY_TEXT");
        expectError(parse("{}"), 400, "EMPTY_TEXT");
        expectError(parse(textBody("가".repeat(20_001))), 413, "TEXT_TOO_LONG");
        assertThat(RECEIVED).isEmpty();

        reply = new FakeReply(200, "{\"questions\": []}", 0);
        parse(textBody("가".repeat(20_000))).andExpect(status().isOk());
    }

    @Test
    void 지원_나누기_AI_오류는_502_AI_FAILED_이고_message_에_AI_code() throws Exception {
        reply = new FakeReply(502, """
                {"error": {"code": "LLM_ERROR", "message": "bad"}}""", 0);

        String body = parse(textBody(POSTING)).andExpect(status().isBadGateway()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat((String) JsonPath.read(body, "$.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(body, "$.error.message")).contains("LLM_ERROR");
    }

    @Test
    void 종류나_직무가_목록_밖이면_null_로_바꾸지_않고_AI_FAILED() throws Exception {
        for (String field : List.of("\"organizationType\": \"COMPANY\"", "\"jobRole\": \"SERVER\"",
                "\"organizationType\": \"\"", "\"jobRole\": \"\"")) {
            reply = new FakeReply(200, "{" + field + ", \"questions\": []}", 0);
            expectError(parse(textBody(POSTING)), 502, "AI_FAILED");
        }
    }

    @Test
    void 응답_규칙을_어긴_지원처_문항은_대신_고치지_않고_AI_FAILED() throws Exception {
        String tooMany = "[" + "\"문항\",".repeat(10) + "\"문항\"]";
        for (String body : List.of(
                "{}",
                "{\"organization\": \" \", \"questions\": []}",
                "{\"organization\": \"" + "가".repeat(101) + "\", \"questions\": []}",
                "{\"questions\": " + tooMany + "}",
                "{\"questions\": [\" \"]}",
                "{\"questions\": [null]}",
                "{\"questions\": [\"같은 문항\", \"같은 문항\"]}",
                "{\"questions\": [\"" + "가".repeat(5_001) + "\"]}")) {
            reply = new FakeReply(200, body, 0);
            expectError(parse(textBody(POSTING)), 502, "AI_FAILED");
        }
    }

    @Test
    void 지원_나누기_제한_시간을_넘기면_504_AI_TIMEOUT() throws Exception {
        reply = new FakeReply(200, "{\"questions\": []}", 2_000);
        expectError(parse(textBody(POSTING)), 504, "AI_TIMEOUT");
    }

    @Test
    void 지원_나누기도_로그인하지_않으면_401() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
        expectError(anonymous.perform(post("/api/entrance/parse-application").contentType(MediaType.APPLICATION_JSON)
                .content(textBody(POSTING))), 401, "UNAUTHENTICATED");
        assertThat(RECEIVED).isEmpty();
    }
}

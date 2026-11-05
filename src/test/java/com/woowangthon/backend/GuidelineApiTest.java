package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/guideline-api.md 와 ai-api.md 「POST /guidelines」(SPEC S-49).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.sync-timeout-seconds=1")
class GuidelineApiTest {

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
        FAKE_AI.createContext("/guidelines", exchange -> {
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
        });
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String SOURCE = "유튜브 「자소서 첫 문장 쓰는 법」";

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
                { "guidelines": [{ "text": "첫 문장에 결과를 쓴다" }, { "text": "문단 하나에 이야기 하나만 쓴다" }], "model": "test-model" }
                """, 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table guideline, analysis_usage");
    }

    private int used() {
        return jdbc.queryForObject("select coalesce(sum(count), 0)::int from analysis_usage where user_id = ?",
                Integer.class, TestLogin.USER_ID);
    }

    private int saved() {
        return jdbc.queryForObject("select count(*)::int from guideline", Integer.class);
    }

    // --- 정리 ---

    @Test
    void 정리는_후보와_출처를_돌려주고_저장하지_않고_1회_센다() throws Exception {
        extract("{\"text\": \"결론부터 써라\", \"source\": \"" + SOURCE + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(SOURCE))
                .andExpect(jsonPath("$.candidates[*].text", contains("첫 문장에 결과를 쓴다", "문단 하나에 이야기 하나만 쓴다")))
                .andExpect(jsonPath("$.model").doesNotExist());

        assertThat(saved()).isZero();
        assertThat(used()).isEqualTo(1);
        // 출처는 AI 에 보내지 않는다
        assertThat(RECEIVED).hasSize(1);
        assertThat((String) JsonPath.read(RECEIVED.getFirst(), "$.text")).isEqualTo("결론부터 써라");
        assertThat(RECEIVED.getFirst()).doesNotContain("source");
    }

    @Test
    void 후보가_없으면_빈_배열이다() throws Exception {
        reply = new FakeReply(200, "{ \"guidelines\": [] }", 0);

        extract(validExtract()).andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void AI_가_실패하면_502_이고_횟수는_센다() throws Exception {
        reply = new FakeReply(502, "{ \"error\": { \"code\": \"LLM_ERROR\", \"message\": \"실패\" } }", 0);

        expectError(extract(validExtract()), 502, "AI_FAILED")
                .andExpect(jsonPath("$.error.message", containsString("LLM_ERROR")));

        assertThat(used()).isEqualTo(1);
        assertThat(saved()).isZero();
    }

    @Test
    void 제한_시간을_넘기면_504_다() throws Exception {
        reply = new FakeReply(200, "{ \"guidelines\": [] }", 3000);

        expectError(extract(validExtract()), 504, "AI_TIMEOUT");

        assertThat(used()).isEqualTo(1);
    }

    @Test
    void AI_응답이_규칙을_벗어나면_고쳐_쓰지_않고_502_다() throws Exception {
        String eleven = IntStream.range(0, 11).mapToObj(i -> "{ \"text\": \"줄 " + i + "\" }")
                .collect(Collectors.joining(", "));
        for (String body : List.of("{ \"model\": \"m\" }", "{ \"guidelines\": [" + eleven + "] }",
                "{ \"guidelines\": [{ \"text\": \" \" }] }", "{ \"guidelines\": [{ \"text\": \"두\\n줄\" }] }",
                "{ \"guidelines\": [{ \"text\": \"" + "가".repeat(101) + "\" }] }")) {
            reply = new FakeReply(200, body, 0);
            expectError(extract(validExtract()), 502, "AI_FAILED");
        }
    }

    @Test
    void 한도를_다_쓰면_AI_를_부르지_않는다() throws Exception {
        jdbc.update("update app_user set daily_analysis_limit = 0 where id = ?", TestLogin.USER_ID);

        expectError(extract(validExtract()), 429, "DAILY_LIMIT_EXCEEDED");

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 정리_요청_오류는_세지_않는다() throws Exception {
        expectError(extract("{\"source\": \"멘토\"}"), 400, "INVALID_REQUEST");
        expectError(extract("{\"text\": \"글\"}"), 400, "INVALID_REQUEST");
        expectError(extract("{\"text\": \"글\", \"source\": \" \"}"), 400, "INVALID_REQUEST");
        expectError(extract("{\"text\": \"글\", \"source\": \"두\\n줄\"}"), 400, "INVALID_REQUEST");
        expectError(extract("{\"text\": \"글\", \"source\": \"" + "가".repeat(101) + "\"}"), 400, "INVALID_REQUEST");
        expectError(extract("{\"text\": \"  \", \"source\": \"멘토\"}"), 400, "EMPTY_TEXT");
        expectError(extract("{\"text\": \"" + "가".repeat(20_001) + "\", \"source\": \"멘토\"}"), 413, "TEXT_TOO_LONG");

        assertThat(used()).isZero();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 정리는_20000자까지_받는다() throws Exception {
        extract("{\"text\": \"" + "가".repeat(20_000) + "\", \"source\": \"멘토\"}").andExpect(status().isOk());
    }

    // --- 저장 · 목록 ---

    @Test
    void 지침이_없으면_빈_목록이다() throws Exception {
        mvc.perform(get("/api/guidelines")).andExpect(status().isOk())
                .andExpect(jsonPath("$.guidelines.length()").value(0));
    }

    @Test
    void 저장하면_켜진_채로_만든_순서대로_보인다() throws Exception {
        int before = used();

        save(lines(2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.guidelines[*].text", contains("지침 0", "지침 1")))
                .andExpect(jsonPath("$.guidelines[0].id").isNumber())
                .andExpect(jsonPath("$.guidelines[0].source").value("멘토 피드백"))
                .andExpect(jsonPath("$.guidelines[0].enabled").value(true))
                .andExpect(jsonPath("$.guidelines[0].createdAt", containsString("+09:00")))
                .andExpect(jsonPath("$.guidelines[0].updatedAt", containsString("+09:00")));
        save(lines(1)).andExpect(status().isCreated());

        mvc.perform(get("/api/guidelines")).andExpect(status().isOk())
                .andExpect(jsonPath("$.guidelines[*].text", contains("지침 0", "지침 1", "지침 0")));
        assertThat(used()).isEqualTo(before);
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 저장_시_11번째부터_꺼진_채다() throws Exception {
        save(lines(8)).andExpect(status().isCreated());

        save(lines(4)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.guidelines[*].enabled", contains(true, true, false, false)));

        assertThat(jdbc.queryForObject("select count(*) from guideline where enabled", Integer.class)).isEqualTo(10);
    }

    @Test
    void 저장하면_50개를_넘을_땐_하나도_저장하지_않는다() throws Exception {
        for (int i = 0; i < 4; i++) {
            save(lines(10)).andExpect(status().isCreated());
        }
        save(lines(9)).andExpect(status().isCreated());

        expectError(save(lines(2)), 409, "TOO_MANY_GUIDELINES");
        assertThat(saved()).isEqualTo(49);

        save(lines(1)).andExpect(status().isCreated());
        assertThat(saved()).isEqualTo(50);
    }

    @Test
    void 저장_요청_오류() throws Exception {
        expectError(save("{}"), 400, "INVALID_REQUEST");
        expectError(save("{\"guidelines\": []}"), 400, "INVALID_REQUEST");
        expectError(save("{\"guidelines\": [null]}"), 400, "INVALID_REQUEST");
        expectError(save(lines(11)), 400, "INVALID_REQUEST");
        expectError(save("{\"guidelines\": [{\"text\": \"글\"}]}"), 400, "INVALID_REQUEST");
        expectError(save("{\"guidelines\": [{\"source\": \"출처\"}]}"), 400, "INVALID_REQUEST");
        expectError(save(line(" ", "출처")), 400, "INVALID_REQUEST");
        expectError(save(line("두\\n줄", "출처")), 400, "INVALID_REQUEST");
        expectError(save(line("글", "두\\r줄")), 400, "INVALID_REQUEST");
        expectError(save(line("가".repeat(201), "출처")), 400, "INVALID_REQUEST");
        expectError(save(line("글", "가".repeat(101))), 400, "INVALID_REQUEST");

        assertThat(saved()).isZero();

        save(line("가".repeat(200), "가".repeat(100))).andExpect(status().isCreated());
    }

    // --- 고치기 · 켜고 끄기 ---

    @Test
    void 보낸_필드만_바꾼다() throws Exception {
        long id = firstId(save(lines(1)));

        update(id, "{\"text\": \"고친 지침\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.text").value("고친 지침"))
                .andExpect(jsonPath("$.source").value("멘토 피드백"))
                .andExpect(jsonPath("$.enabled").value(true));
        update(id, "{\"source\": \"내 생각\", \"enabled\": false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("고친 지침"))
                .andExpect(jsonPath("$.source").value("내 생각"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void 켠_지침은_10개까지다() throws Exception {
        save(lines(10)).andExpect(status().isCreated());
        long off = firstId(save(lines(1)));

        // 11개가 되면 아무것도 바꾸지 않는다 — 같이 보낸 글도
        expectError(update(off, "{\"enabled\": true, \"text\": \"고친 지침\"}"), 409, "TOO_MANY_ENABLED");
        assertThat(jdbc.queryForObject("select text from guideline where id = ?", String.class, off)).isEqualTo("지침 0");

        // 꺼진 것을 고치는 건 된다 · 이미 켜진 것을 다시 켜는 건 세지 않는다
        update(off, "{\"text\": \"꺼진 채 고침\"}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        long on = jdbc.queryForObject("select min(id) from guideline", Long.class);
        update(on, "{\"enabled\": true}").andExpect(status().isOk());

        // 하나를 끄면 켤 수 있다
        update(on, "{\"enabled\": false}").andExpect(status().isOk());
        update(off, "{\"enabled\": true}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void 고치기_오류() throws Exception {
        long id = firstId(save(lines(1)));

        expectError(update(id, "{}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"다른칸\": 1}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"text\": null}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"text\": \" \"}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"text\": \"" + "가".repeat(201) + "\"}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"source\": \"두\\n줄\"}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"enabled\": null}"), 400, "INVALID_REQUEST");
        expectError(update(id, "{\"enabled\": \"켜기\"}"), 400, "INVALID_REQUEST");
        expectError(update(id + 1000, "{\"enabled\": false}"), 404, "GUIDELINE_NOT_FOUND");
    }

    // --- 지우기 · 사용자 ---

    @Test
    void 지우면_목록에서_빠지고_다시_지우면_404_다() throws Exception {
        long id = firstId(save(lines(1)));

        mvc.perform(delete("/api/guidelines/{id}", id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/guidelines")).andExpect(jsonPath("$.guidelines.length()").value(0));
        expectError(mvc.perform(delete("/api/guidelines/{id}", id)), 404, "GUIDELINE_NOT_FOUND");
    }

    @Test
    void 다른_사용자의_지침은_없는_것과_같고_한도도_따로_센다() throws Exception {
        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            long id = firstId(save(lines(10)));
            MockMvc otherMvc = TestLogin.mvc(context, other);

            otherMvc.perform(get("/api/guidelines")).andExpect(jsonPath("$.guidelines.length()").value(0));
            expectError(otherMvc.perform(patch("/api/guidelines/{id}", id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\": false}")), 404, "GUIDELINE_NOT_FOUND");
            expectError(otherMvc.perform(delete("/api/guidelines/{id}", id)), 404, "GUIDELINE_NOT_FOUND");
            otherMvc.perform(post("/api/guidelines").contentType(MediaType.APPLICATION_JSON).content(lines(1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.guidelines[0].enabled").value(true));
        } finally {
            jdbc.execute("truncate table guideline");
            jdbc.update("delete from app_user where id = ?", other);
        }
    }

    // --- 도우미 ---

    private static String validExtract() {
        return "{\"text\": \"결론부터 써라\", \"source\": \"멘토 피드백\"}";
    }

    private static String lines(int count) {
        return IntStream.range(0, count).mapToObj(i -> "{\"text\": \"지침 " + i + "\", \"source\": \"멘토 피드백\"}")
                .collect(Collectors.joining(", ", "{\"guidelines\": [", "]}"));
    }

    private static String line(String text, String source) {
        return "{\"guidelines\": [{\"text\": \"" + text + "\", \"source\": \"" + source + "\"}]}";
    }

    private ResultActions extract(String json) throws Exception {
        return mvc.perform(post("/api/guidelines/extract").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions save(String json) throws Exception {
        return mvc.perform(post("/api/guidelines").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions update(long id, String json) throws Exception {
        return mvc.perform(patch("/api/guidelines/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long firstId(ResultActions saved) throws Exception {
        String body = saved.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.guidelines[0].id")).longValue();
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

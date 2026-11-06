package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.chagok.InterruptedRunCleaner;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * ETC docs/contracts/chagok-api.md · ai-api.md 「POST /agent」 · mcp-api.md 「토큰 범위」(S-31).
 * AI 서버 자리에 NDJSON 을 한 줄씩 흘리는 가짜 HTTP 서버를 띄운다(테스트 전용 — 앱은 진짜 AI 서버만 부른다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.chagok.timeout-seconds=2", "app.mcp.internal-base-url=http://be.test/mcp"})
class ChagokApiTest {

    static final HttpServer FAKE_AI;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile List<String> lines = List.of();
    static volatile int replyStatus = 200;
    // 줄을 다 흘린 뒤 닫지 않고 기다린다 — 그만두기 · 제한 시간
    static volatile CountDownLatch hold;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        FAKE_AI.createContext("/agent", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            try {
                exchange.sendResponseHeaders(replyStatus, 0);
                OutputStream out = exchange.getResponseBody();
                for (String line : lines) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                CountDownLatch latch = hold;
                if (latch != null) {
                    latch.await(10, TimeUnit.SECONDS);
                }
            } catch (IOException | InterruptedException ignored) {
                // BE 가 먼저 끊은 경우
            }
            exchange.close();
        });
        FAKE_AI.createContext("/match", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        FAKE_AI.createContext("/extract", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String SEARCH_CALL = """
            {"type":"tool_call","id":"c1","tool":"search_experiences","arguments":{"query":"WebSocket"},"label":"「WebSocket」으로 경험 찾는 중"}""";
    static final String SEARCH_RESULT = """
            {"type":"tool_result","id":"c1","ok":true,"summary":"경험 1개를 찾았어요","result":{"total":1,"experiences":[]},"error":null}""";
    static final String MESSAGE = """
            {"type":"message","text":"이 경험이 맞아요."}""";
    static final String CONFIRM_APPLICATION = """
            {"type":"confirm","tool":"create_application","arguments":{"organization":"OO 부트캠프","organizationType":"BOOTCAMP","jobRole":"BACKEND","postingUrl":null,"questions":[{"text":"문제를 해결한 경험을 적어 주세요."}]},"label":"「OO 부트캠프」 지원을 만들까요?"}""";
    static final String DONE = "{\"type\":\"done\"}";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    InterruptedRunCleaner cleaner;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        cleanTables();
        RECEIVED.clear();
        replyStatus = 200;
        hold = null;
        lines = List.of();
        mvc = TestLogin.mvc(context);
    }

    @AfterEach
    void release() {
        if (hold != null) {
            hold.countDown();
        }
    }

    private void cleanTables() {
        jdbc.update("delete from chagok_run");
        jdbc.update("delete from analysis_job_activity");
        jdbc.update("delete from analysis_job");
        jdbc.update("delete from question");
        jdbc.update("delete from application");
        jdbc.update("delete from source");
        jdbc.update("delete from analysis_usage");
        jdbc.update("delete from access_token");
    }

    @Test
    void 단계를_쌓고_토큰을_지운다() throws Exception {
        lines = List.of(SEARCH_CALL, SEARCH_RESULT, MESSAGE, DONE);
        long runId = start("WebSocket 경험 찾아 줘");
        String run = await(runId, "DONE");

        assertThat((Integer) JsonPath.read(run, "$.steps.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(run, "$.steps[0].seq")).isEqualTo(1);
        assertThat((String) JsonPath.read(run, "$.steps[0].type")).isEqualTo("TOOL_CALL");
        assertThat((String) JsonPath.read(run, "$.steps[0].tool")).isEqualTo("search_experiences");
        assertThat((Boolean) JsonPath.read(run, "$.steps[0].ok")).isTrue();
        assertThat((String) JsonPath.read(run, "$.steps[0].summary")).isEqualTo("경험 1개를 찾았어요");
        assertThat((Integer) JsonPath.read(run, "$.steps[0].result.total")).isEqualTo(1);
        assertThat(run).doesNotContain("callId");
        assertThat((String) JsonPath.read(run, "$.steps[1].type")).isEqualTo("MESSAGE");
        assertThat((String) JsonPath.read(run, "$.steps[1].text")).isEqualTo("이 경험이 맞아요.");
        assertThat((Object) JsonPath.read(run, "$.result")).isNull();
        assertThat((Object) JsonPath.read(run, "$.error")).isNull();

        // AI 에 간 요청 — 내부 주소 + 이 일의 토큰, 맥락 칸은 null 로
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.mcpUrl")).startsWith("http://be.test/mcp/");
        assertThat((Integer) JsonPath.read(sent, "$.maxToolCalls")).isEqualTo(8);
        assertThat((Object) JsonPath.read(sent, "$.context.question")).isNull();
        // 일이 끝나면 401, 한도는 1회
        awaitChagokTokensGone();
        mvc.perform(post(path(JsonPath.read(sent, "$.mcpUrl"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
        assertThat(usage()).isEqualTo(1);
    }

    @Test
    void 일하는_동안_MCP_는_쓰기를_보이되_실행하지_않는다() throws Exception {
        hold = new CountDownLatch(1);
        lines = List.of(MESSAGE);
        long runId = start("지원 만들어 줘");
        awaitReceived();
        String mcp = path(JsonPath.read(RECEIVED.getFirst(), "$.mcpUrl"));

        mvc.perform(rpc(mcp, "tools/list", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools", hasSize(5)))
                .andExpect(jsonPath("$.result.tools[?(@.name=='create_application')].annotations.readOnlyHint").value(false))
                .andExpect(jsonPath("$.result.tools[?(@.name=='create_application')].inputSchema.properties.postingUrl.description")
                        .value("공고 원문 링크 — 사용자가 준 http(s) 링크만. 없으면 null"))
                .andExpect(jsonPath("$.result.tools[?(@.name=='add_source')].annotations.readOnlyHint").value(false));
        mvc.perform(rpc(mcp, "tools/call", "{\"name\":\"add_source\",\"arguments\":{\"text\":\"글\"}}"))
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(org.hamcrest.Matchers.containsString("CONFIRM_REQUIRED")));
        assertThat(jdbc.queryForObject("select count(*) from source", Long.class)).isZero();

        // 연결 화면에 안 보이고, 연결을 만들어도 CHAGOK 토큰은 살아 있다
        mvc.perform(get("/api/mcp/connection")).andExpect(jsonPath("$.connected").value(false));
        String read = JsonPath.read(mvc.perform(post("/api/mcp/connection")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), "$.url");
        mvc.perform(rpc(mcp, "ping", "{}")).andExpect(status().isOk());

        // READ 토큰엔 그대로 3개, 쓰기 도구는 없는 도구
        String readPath = path(read);
        mvc.perform(rpc(readPath, "tools/list", "{}")).andExpect(jsonPath("$.result.tools", hasSize(3)));
        mvc.perform(rpc(readPath, "tools/call", "{\"name\":\"add_source\",\"arguments\":{\"text\":\"글\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));

        mvc.perform(post("/api/chagok/runs/" + runId + "/cancel")).andExpect(jsonPath("$.status").value("CANCELED"));
        awaitChagokTokensGone();
    }

    @Test
    void 만료된_토큰은_401() throws Exception {
        hold = new CountDownLatch(1);
        lines = List.of(MESSAGE);
        long runId = start("찾아 줘");
        awaitReceived();
        jdbc.update("update access_token set expires_at = now() - interval '1 second' where scope = 'CHAGOK'");
        mvc.perform(rpc(path(JsonPath.read(RECEIVED.getFirst(), "$.mcpUrl")), "ping", "{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chagok/runs/" + runId + "/cancel"));
    }

    @Test
    void 확인하면_지원을_만들고_두_번_눌러도_한_번() throws Exception {
        lines = List.of(SEARCH_CALL, SEARCH_RESULT, CONFIRM_APPLICATION);
        long runId = start("이걸로 지원 만들어 줘");
        String waiting = await(runId, "WAITING_CONFIRM");
        assertThat((String) JsonPath.read(waiting, "$.steps[1].type")).isEqualTo("CONFIRM");
        assertThat((String) JsonPath.read(waiting, "$.steps[1].arguments.organization")).isEqualTo("OO 부트캠프");
        // 확인 전엔 아무것도 저장하지 않는다
        assertThat(jdbc.queryForObject("select count(*) from application", Long.class)).isZero();
        // 확인 카드는 GET /api/work 로 다시 찾는다(S-47)
        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.inProgress[0].runId").value(runId))
                .andExpect(jsonPath("$.inProgress[0].status").value("WAITING_CONFIRM"));

        String done = mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.tool").value("create_application"))
                .andExpect(jsonPath("$.result.questionIds", hasSize(1)))
                .andExpect(jsonPath("$.error", nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((Object) JsonPath.read(done, "$.result.applicationId")).isNotNull();

        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_WAITING"));
        assertThat(jdbc.queryForObject("select count(*) from application", Long.class)).isEqualTo(1);
        // 일 1회 + 문항 1개
        assertThat(usage()).isEqualTo(2);
        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.recent[?(@.runId == " + runId + ")].status").value("DONE"))
                .andExpect(jsonPath("$.recent[?(@.runId == " + runId + ")].finishedAt").isNotEmpty());
    }

    @Test
    void 동시에_확인해도_한_번만_실행한다() throws Exception {
        lines = List.of(CONFIRM_APPLICATION);
        long runId = start("지원 만들어 줘");
        await(runId, "WAITING_CONFIRM");

        List<Integer> statuses = together(() -> mvc.perform(post("/api/chagok/runs/" + runId + "/confirm")));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from application", Long.class)).isEqualTo(1);
    }

    @Test
    void 동시에_시작해도_일은_하나() throws Exception {
        hold = new CountDownLatch(1);
        lines = List.of(MESSAGE);

        List<Integer> statuses = together(() -> postRun(json("찾아 줘")));
        assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        assertThat(jdbc.queryForObject("select count(*) from chagok_run", Long.class)).isEqualTo(1);
        assertThat(usage()).isEqualTo(1);
        hold.countDown();
        awaitChagokTokensGone();
    }

    @Test
    void 확인하면_자료를_넣는다() throws Exception {
        lines = List.of("""
                {"type":"confirm","tool":"add_source","arguments":{"text":"회고 글 그대로"},"label":"이 글을 자료로 넣을까요?"}""");
        long runId = start("회고 글 그대로 — 이거 쌓아 줘");
        await(runId, "WAITING_CONFIRM");

        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.tool").value("add_source"))
                .andExpect(jsonPath("$.result.jobId").isNumber());
        assertThat(jdbc.queryForObject("select text from source", String.class)).isEqualTo("회고 글 그대로");
        assertThat(jdbc.queryForObject("select kind from source", String.class)).isEqualTo("TEXT");
    }

    @Test
    void 확인_뒤_실행이_실패하면_FAILED_이고_아무것도_남지_않는다() throws Exception {
        lines = List.of("""
                {"type":"confirm","tool":"create_application","arguments":{"organization":"OO","questions":[]},"label":"만들까요?"}""");
        long runId = start("지원 만들어 줘");
        await(runId, "WAITING_CONFIRM");

        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("select count(*) from application", Long.class)).isZero();
    }

    @Test
    void 확인하면_공고_원문_링크를_지원에_남긴다() throws Exception {
        lines = List.of(confirmWithPostingUrl("\"https://example.com/recruit/1234?ref=a\""));
        long runId = start("이 공고로 지원 만들어 줘 https://example.com/recruit/1234?ref=a");
        String waiting = await(runId, "WAITING_CONFIRM");
        // 확인 카드에서 사용자가 링크를 본다
        assertThat((String) JsonPath.read(waiting, "$.steps[0].arguments.postingUrl"))
                .isEqualTo("https://example.com/recruit/1234?ref=a");

        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(jdbc.queryForObject("select posting_url from application", String.class))
                .isEqualTo("https://example.com/recruit/1234?ref=a");
    }

    @Test
    void 확인_뒤_공고_링크가_틀리면_POST_applications_와_같은_코드로_FAILED() throws Exception {
        lines = List.of(confirmWithPostingUrl("\"ftp://example.com/recruit\""));
        long runId = start("이 공고로 지원 만들어 줘");
        await(runId, "WAITING_CONFIRM");

        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("postingUrl")));
        assertThat(jdbc.queryForObject("select count(*) from application", Long.class)).isZero();
    }

    private static String confirmWithPostingUrl(String postingUrl) {
        return """
                {"type":"confirm","tool":"create_application","arguments":{"organization":"OO","postingUrl":%s,"questions":[{"text":"문항"}]},"label":"만들까요?"}"""
                .formatted(postingUrl);
    }

    @Test
    void 확인_뒤_한도가_모자라면_FAILED_에_코드를_그대로() throws Exception {
        lines = List.of(CONFIRM_APPLICATION);
        long runId = start("지원 만들어 줘");
        await(runId, "WAITING_CONFIRM");
        jdbc.update("update analysis_usage set count = 20");

        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("DAILY_LIMIT_EXCEEDED"));
    }

    @Test
    void AI_오류_이벤트면_앞_단계를_남기고_실패() throws Exception {
        lines = List.of(SEARCH_CALL, SEARCH_RESULT, "{\"type\":\"error\",\"code\":\"LLM_ERROR\",\"message\":\"모델 오류\"}");
        long runId = start("찾아 줘");
        String failed = await(runId, "FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("LLM_ERROR");
        assertThat((Integer) JsonPath.read(failed, "$.steps.length()")).isEqualTo(1);
    }

    @Test
    void 도구_오류는_일을_끝내지_않는다() throws Exception {
        lines = List.of(SEARCH_CALL, """
                {"type":"tool_result","id":"c1","ok":false,"summary":"못 찾았어요","result":null,"error":{"code":"INVALID_ARGUMENT","message":"틀림"}}""",
                DONE);
        String run = await(start("찾아 줘"), "DONE");
        assertThat((Boolean) JsonPath.read(run, "$.steps[0].ok")).isFalse();
        assertThat((String) JsonPath.read(run, "$.steps[0].error.code")).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void 계약_밖_이벤트는_INVALID_AGENT_OUTPUT() throws Exception {
        List<List<String>> broken = List.of(
                List.of("JSON 아님"),
                List.of("{\"type\":\"thinking\"}"),
                List.of("{\"type\":\"tool_result\",\"id\":\"없음\",\"ok\":true,\"summary\":\"요약\"}"),
                List.of("{\"type\":\"tool_call\",\"id\":\"c1\",\"tool\":\"add_source\",\"arguments\":{},\"label\":\"쓰기\"}"),
                List.of("{\"type\":\"confirm\",\"tool\":\"search_experiences\",\"arguments\":{},\"label\":\"읽기\"}"),
                List.of("{\"type\":\"message\",\"text\":\"" + "가".repeat(301) + "\"}"),
                List.of(MESSAGE));
        for (List<String> stream : broken) {
            lines = stream;
            String failed = await(start("찾아 줘"), "FAILED");
            assertThat((String) JsonPath.read(failed, "$.error.code")).as(stream.toString()).isEqualTo("INVALID_AGENT_OUTPUT");
        }
    }

    @Test
    void AI_가_5xx_면_AI_UNAVAILABLE() throws Exception {
        replyStatus = 503;
        String failed = await(start("찾아 줘"), "FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void 제한_시간을_넘으면_끊고_AI_TIMEOUT() throws Exception {
        hold = new CountDownLatch(1);
        lines = List.of(MESSAGE);
        String failed = await(start("찾아 줘"), "FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_TIMEOUT");
        assertThat((Integer) JsonPath.read(failed, "$.steps.length()")).isEqualTo(1);
        awaitChagokTokensGone();
    }

    @Test
    void 진행_중에_그만두면_스트림을_끊고_토큰을_지운다() throws Exception {
        hold = new CountDownLatch(1);
        lines = List.of(MESSAGE);
        long runId = start("찾아 줘");
        awaitReceived();
        mvc.perform(post("/api/chagok/runs/" + runId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        // 제한 시간(2초)보다 빨리 끝나야 스트림을 끊은 것이다
        awaitChagokTokensGone();
        mvc.perform(get("/api/chagok/runs/" + runId)).andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void 확인을_기다리는_일을_그만두면_실행하지_않는다() throws Exception {
        lines = List.of(CONFIRM_APPLICATION);
        long runId = start("지원 만들어 줘");
        await(runId, "WAITING_CONFIRM");
        mvc.perform(post("/api/chagok/runs/" + runId + "/cancel")).andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(post("/api/chagok/runs/" + runId + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_WAITING"));
        // 끝난 일은 그대로
        mvc.perform(post("/api/chagok/runs/" + runId + "/cancel")).andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void 동시에_하나_한도_입력_검사() throws Exception {
        hold = new CountDownLatch(1);
        lines = List.of(MESSAGE);
        long runId = start("찾아 줘");
        postRun("{\"text\":\"하나 더\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_IN_PROGRESS"));
        assertThat(usage()).isEqualTo(1);
        mvc.perform(post("/api/chagok/runs/" + runId + "/cancel"));

        postRun("{\"text\":\"   \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("EMPTY_TEXT"));
        postRun("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        postRun("{\"text\":\"글\",\"questionId\":\"문항\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        postRun("{\"text\":\"" + "가".repeat(50001) + "\"}").andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.code").value("TEXT_TOO_LONG"));
        postRun("{\"text\":\"글\",\"questionId\":999999}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUESTION_NOT_FOUND"));

        jdbc.update("update analysis_usage set count = 20");
        postRun("{\"text\":\"글\"}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("DAILY_LIMIT_EXCEEDED"));
    }

    @Test
    void 문항에서_열면_문항을_맥락으로_싣는다() throws Exception {
        lines = List.of(DONE);
        String submitted = mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content("""
                {"organization":"OO 부트캠프","questions":[{"text":"문제를 해결한 경험"}]}"""))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long questionId = ((Number) JsonPath.read(submitted, "$.questionIds[0]")).longValue();

        long runId = id(postRun("{\"text\":\"근거 찾아 줘\",\"questionId\":" + questionId + "}"));
        String done = await(runId, "DONE");
        assertThat(((Number) JsonPath.read(done, "$.questionId")).longValue()).isEqualTo(questionId);
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.context.question.text")).isEqualTo("문제를 해결한 경험");
        assertThat((String) JsonPath.read(sent, "$.context.question.organization")).isEqualTo("OO 부트캠프");
    }

    @Test
    void 남의_일은_없는_것과_같다() throws Exception {
        AppUser other = users.save(new AppUser("chagok-other-" + System.nanoTime(), "hash", null));
        MockMvc stranger = TestLogin.mvc(context, other.getId());
        lines = List.of(CONFIRM_APPLICATION);
        long runId = start("지원 만들어 줘");
        await(runId, "WAITING_CONFIRM");

        for (ResultActions result : List.of(stranger.perform(get("/api/chagok/runs/" + runId)),
                stranger.perform(post("/api/chagok/runs/" + runId + "/confirm")),
                stranger.perform(post("/api/chagok/runs/" + runId + "/cancel")))) {
            result.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
        }
        // 남의 문항도
        String submitted = mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content("""
                {"organization":"OO","questions":[{"text":"문항"}]}"""))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        stranger.perform(post("/api/chagok/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"글\",\"questionId\":" + JsonPath.read(submitted, "$.questionIds[0]") + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/chagok/runs/" + runId)).andExpect(jsonPath("$.status").value("WAITING_CONFIRM"));
    }

    @Test
    void 서버가_다시_시작되면_끊긴_일을_실패로_돌린다() throws Exception {
        jdbc.update("insert into chagok_run (user_id, text, status, steps) values (1, '글', 'RUNNING', '[]')");
        cleaner.run(null);
        assertThat(jdbc.queryForObject("select status from chagok_run", String.class)).isEqualTo("FAILED");
    }

    private interface Call {
        ResultActions send() throws Exception;
    }

    // 같은 요청을 두 스레드에서 한꺼번에 보낸다
    private static List<Integer> together(Call call) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> send = () -> {
                ready.countDown();
                ready.await();
                return call.send().andReturn().getResponse().getStatus();
            };
            var first = pool.submit(send);
            var second = pool.submit(send);
            return List.of(first.get(), second.get());
        }
    }

    private long start(String text) throws Exception {
        return id(postRun(json(text)));
    }

    private static String json(String text) {
        return "{\"text\":\"" + text + "\",\"questionId\":null}";
    }

    private ResultActions postRun(String body) throws Exception {
        return mvc.perform(post("/api/chagok/runs").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions result) throws Exception {
        String body = result.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.runId")).longValue();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rpc(String path, String method,
            String params) {
        return post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}");
    }

    // http://be.test/mcp/<token> → /mcp/<token>
    private static String path(String url) {
        return url.substring(url.indexOf("/mcp/"));
    }

    private String await(long runId, String expected) throws Exception {
        String body = "";
        for (int i = 0; i < 500; i++) {
            body = mvc.perform(get("/api/chagok/runs/" + runId)).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            String status = JsonPath.read(body, "$.status");
            if (status.equals(expected)) {
                return body;
            }
            if (!status.equals("RUNNING")) {
                break;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(expected + " 이어야 하는데: " + body);
    }

    private void awaitReceived() throws Exception {
        for (int i = 0; i < 300 && RECEIVED.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertThat(RECEIVED).isNotEmpty();
    }

    private void awaitChagokTokensGone() throws Exception {
        for (int i = 0; i < 150; i++) {
            if (chagokTokens() == 0) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(chagokTokens()).isZero();
    }

    private long chagokTokens() {
        return jdbc.queryForObject("select count(*) from access_token where scope = 'CHAGOK'", Long.class);
    }

    private int usage() {
        return jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage", Integer.class);
    }
}

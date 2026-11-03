package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.ExtractRequest;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.job.AnalysisJob;
import com.woowangthon.backend.job.AnalysisJobRepository;
import com.woowangthon.backend.job.InterruptedJobCleaner;
import com.woowangthon.backend.job.JobStatus;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/experience-api.md 「자료 넣기」 · 「작업 상태」 · 「다시 시도」.
 * AI 서버 자리에 가짜 HTTP 서버를 띄워 ai-api.md 응답 · 오류를 돌려준다(테스트 전용 — 앱은 진짜 AI 서버만 부른다).
 * 분석이 다른 스레드에서 돌아 트랜잭션 롤백을 못 쓰므로 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=1")
class AnalysisJobApiTest {

    record FakeReply(int status, String body, long delayMillis) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    // uvicorn 은 Upgrade: h2c 요청을 400 으로 거절한다 — BE 는 HTTP/1.1 로만 부른다
    static final List<String> UPGRADE_HEADERS = new CopyOnWriteArrayList<>();
    static volatile FakeReply reply;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        FAKE_AI.createContext("/extract", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            if (upgrade != null) {
                UPGRADE_HEADERS.add(upgrade);
            }
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

    // ai-api.md 응답 예시 그대로
    static final String CONTRACT_EXAMPLE = """
            {
              "activities": [
                {
                  "name": "바꿔요 — 캠퍼스 중고거래 앱",
                  "period": { "start": "2025-03", "end": "2025-06", "ongoing": false },
                  "experiences": [
                    {
                      "content": "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                      "competencies": ["PROBLEM_SOLVING", "IMPLEMENTATION"], "actor": "ME", "sourceStatus": "SOURCE_CONFIRMED",
                      "citations": [{ "quote": "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.",
                                      "matched": true, "location": { "startLine": 5, "endLine": 5 } }]
                    },
                    {
                      "content": "서비스를 AWS 에 배포했다",
                      "competencies": ["IMPLEMENTATION"], "actor": "TEAM", "sourceStatus": "SOURCE_CONFIRMED",
                      "citations": [{ "quote": "배포는 팀원이 맡아 AWS 에 올렸다.", "matched": true,
                                      "location": { "startLine": 7, "endLine": 7 } }]
                    },
                    {
                      "content": "백엔드 API 설계를 맡았다",
                      "competencies": ["IMPLEMENTATION"], "actor": "ME", "sourceStatus": "NEEDS_REVIEW",
                      "citations": [{ "quote": "나는 백엔드 API 설계를 전부 맡았다.", "matched": false, "location": null }]
                    }
                  ]
                },
                {
                  "name": "알고리즘 스터디",
                  "period": { "start": "2024-09", "end": null, "ongoing": true },
                  "experiences": [
                    {
                      "content": "매주 풀 문제를 고른다",
                      "competencies": ["OPERATION"], "actor": "ME", "sourceStatus": "NEEDS_REVIEW",
                      "citations": [{ "quote": "내가 문제 선정을 맡는다.", "matched": null, "location": null }]
                    }
                  ]
                }
              ],
              "model": "test-model"
            }
            """;

    static final String SOURCE_TEXT = "# 바꿔요 회고\n\n기간: 2025-03 ~ 2025-06\n\n"
            + "- 채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.\n"
            + "- 팀은 매주 금요일 스프린트 회고를 했다.\n- 배포는 팀원이 맡아 AWS 에 올렸다.\r\n  끝 공백  \n";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnalysisJobRepository jobs;

    @Autowired
    SourceRepository sources;

    @Autowired
    InterruptedJobCleaner cleaner;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        RECEIVED.clear();
        UPGRADE_HEADERS.clear();
        reply = new FakeReply(200, CONTRACT_EXAMPLE, 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("truncate table analysis_job_activity, analysis_job, citation, experience, activity, source");
    }

    // --- 자료 넣기 → 완료 ---

    @Test
    void 파일을_넣으면_분석되고_활동_경험_근거가_저장된다() throws Exception {
        String body = JsonPath.parse(java.util.Map.of("kind", "FILE", "name", "바꿔요-회고.md", "text", SOURCE_TEXT))
                .jsonString();
        String submitted = mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) JsonPath.read(submitted, "$.jobId")).longValue();
        long sourceId = ((Number) JsonPath.read(submitted, "$.sourceId")).longValue();

        String done = awaitDone(jobId);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(done, "$.error")).isNull();
        assertThat(((Number) JsonPath.read(done, "$.sourceId")).longValue()).isEqualTo(sourceId);
        assertThat((String) JsonPath.read(done, "$.createdAt")).matches(".*\\+09:00");
        List<Integer> activityIds = JsonPath.read(done, "$.activityIds");
        assertThat(activityIds).hasSize(2);

        // AI 서버에는 저장한 원본을 글자 하나 다르지 않게 보낸다
        assertThat(RECEIVED).hasSize(1);
        assertThat(UPGRADE_HEADERS).isEmpty();
        assertThat((String) JsonPath.read(RECEIVED.getFirst(), "$.text")).isEqualTo(SOURCE_TEXT);
        assertThat((String) JsonPath.read(RECEIVED.getFirst(), "$.sourceName")).isEqualTo("바꿔요-회고.md");
        assertThat(sources.findById(sourceId).orElseThrow().getText()).isEqualTo(SOURCE_TEXT);

        mvc.perform(get("/api/activities/{id}", activityIds.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.period.start").value("2025-03"))
                .andExpect(jsonPath("$.experiences.length()").value(3))
                .andExpect(jsonPath("$.experiences[0].competencies[0]").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.experiences[0].competencies[1]").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.experiences[0].competenciesBy").value("AI"))
                .andExpect(jsonPath("$.experiences[0].sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.experiences[0].citations[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.experiences[0].citations[0].sourceName").value("바꿔요-회고.md"))
                .andExpect(jsonPath("$.experiences[0].citations[0].matched").value(true))
                .andExpect(jsonPath("$.experiences[0].citations[0].location.startLine").value(5))
                .andExpect(jsonPath("$.experiences[1].actor").value("TEAM"))
                .andExpect(jsonPath("$.experiences[2].sourceStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.experiences[2].citations[0].matched").value(false))
                .andExpect(jsonPath("$.experiences[2].citations[0].location").value(nullValue()));

        mvc.perform(get("/api/activities/{id}", activityIds.get(1)))
                .andExpect(jsonPath("$.period.ongoing").value(true))
                .andExpect(jsonPath("$.experiences[0].citations[0].matched").value(nullValue()));
    }

    @Test
    void 붙여넣은_텍스트는_BE_가_이름을_붙인다() throws Exception {
        long jobId = submit("""
                {"kind": "TEXT", "name": null, "text": "알고리즘 스터디에서 문제 선정을 맡았다."}""");
        awaitDone(jobId);

        assertThat((String) JsonPath.read(RECEIVED.getFirst(), "$.sourceName"))
                .matches("붙여넣은 텍스트 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[0].name").value("알고리즘 스터디"));
    }

    @Test
    void 활동을_못_찾으면_완료이고_활동은_비어_있다() throws Exception {
        reply = new FakeReply(200, """
                {"activities": [], "model": "test-model"}""", 0);
        String done = awaitDone(submitText());
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.activityIds")).isEmpty();
    }

    @Test
    void 같은_자료를_두_번_넣으면_새_원본_새_활동이_생긴다() throws Exception {
        awaitDone(submitText());
        awaitDone(submitText());
        mvc.perform(get("/api/activities")).andExpect(jsonPath("$.activities.length()").value(4));
    }

    // --- 실패 → 다시 시도 ---

    @Test
    void AI_가_오류를_주면_실패와_이유가_남고_다시_시도하면_된다() throws Exception {
        reply = new FakeReply(502, """
                {"error": {"code": "LLM_ERROR", "message": "rate limit"}}""", 0);
        long jobId = submitText();
        String failed = awaitDone(jobId);
        assertThat((String) JsonPath.read(failed, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.message")).contains("LLM_ERROR");
        assertThat((List<?>) JsonPath.read(failed, "$.activityIds")).isEmpty();
        mvc.perform(get("/api/activities")).andExpect(jsonPath("$.activities.length()").value(0));

        reply = new FakeReply(200, CONTRACT_EXAMPLE, 0);
        mvc.perform(post("/api/jobs/{id}/retry", jobId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.error").value(nullValue()));

        String done = awaitDone(jobId);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.activityIds")).hasSize(2);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void 역량이_없으면_빈_배열이고_목록_밖_값_중복_넘친_값만_버린다() throws Exception {
        reply = new FakeReply(200, """
                {"activities": [{"name": "스터디", "period": null, "experiences": [
                  {"content": "문제를 골랐다", "actor": "ME", "sourceStatus": "NEEDS_REVIEW", "citations": []},
                  {"content": "발표를 했다", "competencies": ["협업", "PRESENTATION", "PRESENTATION", "LEARNING", "ANALYSIS", "PLANNING"],
                   "actor": "ME", "sourceStatus": "NEEDS_REVIEW", "citations": []}]}]}""", 0);
        String done = awaitDone(submitText());
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        List<Integer> activityIds = JsonPath.read(done, "$.activityIds");

        mvc.perform(get("/api/activities/{id}", activityIds.get(0)))
                .andExpect(jsonPath("$.experiences[0].competencies.length()").value(0))
                .andExpect(jsonPath("$.experiences[1].competencies.length()").value(3))
                .andExpect(jsonPath("$.experiences[1].competencies[0]").value("PRESENTATION"))
                .andExpect(jsonPath("$.experiences[1].competencies[1]").value("LEARNING"))
                .andExpect(jsonPath("$.experiences[1].competencies[2]").value("ANALYSIS"));
    }

    @Test
    void 제한_시간을_넘기면_AI_TIMEOUT() throws Exception {
        reply = new FakeReply(200, CONTRACT_EXAMPLE, 2_500);
        String failed = awaitDone(submitText());
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_TIMEOUT");
        mvc.perform(get("/api/activities")).andExpect(jsonPath("$.activities.length()").value(0));
    }

    @Test
    void 확인하지_않은_근거로_원문에서_확인이라_하면_저장하지_않고_실패() throws Exception {
        reply = new FakeReply(200, """
                {"activities": [{"name": "스터디", "period": null, "experiences": [
                  {"content": "문제를 골랐다", "competencies": ["OPERATION"], "actor": "ME", "sourceStatus": "SOURCE_CONFIRMED",
                   "citations": [{"quote": "내가 문제 선정을 맡는다.", "matched": null, "location": null}]}]}]}""", 0);
        String failed = awaitDone(submitText());
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.message")).startsWith("INVALID_RESPONSE");
        mvc.perform(get("/api/activities")).andExpect(jsonPath("$.activities.length()").value(0));
    }

    @Test
    void 계약과_다른_응답이면_실패() throws Exception {
        reply = new FakeReply(200, """
                {"activities": [{"name": "스터디", "period": null, "experiences": [
                  {"content": "문제를 골랐다", "competencies": ["OPERATION"], "actor": "BOSS", "sourceStatus": "NEEDS_REVIEW",
                   "citations": []}]}]}""", 0);
        String failed = awaitDone(submitText());
        assertThat((String) JsonPath.read(failed, "$.error.message")).contains("actor");

        reply = new FakeReply(200, "not json", 0);
        failed = awaitDone(submitText());
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_FAILED");
    }

    @Test
    void AI_서버가_없으면_AI_UNAVAILABLE() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AiClient client = new AiClient("http://127.0.0.1:" + closedPort, 1);
        assertThatThrownBy(() -> client.extract(new ExtractRequest("a.md", "text")))
                .isInstanceOfSatisfying(AiCallException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
    }

    @Test
    void 서버가_다시_켜지면_분석_중이던_작업은_실패가_된다() {
        Source source = sources.save(new Source(CurrentUser.id(), SourceKind.TEXT, "끊긴 자료", "내용"));
        AnalysisJob job = jobs.save(new AnalysisJob(CurrentUser.id(), source));

        cleaner.run(null);

        AnalysisJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    // --- 오류 ---

    @Test
    void 다시_시도_오류() throws Exception {
        long jobId = submitText();
        awaitDone(jobId);
        expectError(mvc.perform(post("/api/jobs/{id}/retry", jobId)), 409, "JOB_NOT_FAILED");
        expectError(mvc.perform(post("/api/jobs/{id}/retry", 999_999)), 404, "JOB_NOT_FOUND");
        expectError(mvc.perform(get("/api/jobs/{id}", 999_999)), 404, "JOB_NOT_FOUND");
    }

    @Test
    void 자료_넣기_오류() throws Exception {
        expectError(postSource("""
                {"kind": "TEXT", "name": null, "text": "   \\n "}"""), 400, "EMPTY_TEXT");
        expectError(postSource("""
                {"kind": "FILE", "name": "slides.pdf", "text": "내용"}"""), 400, "UNSUPPORTED_FILE_TYPE");
        expectError(postSource("""
                {"kind": "FILE", "name": null, "text": "내용"}"""), 400, "INVALID_REQUEST");
        expectError(postSource("""
                {"kind": "PDF", "name": null, "text": "내용"}"""), 400, "INVALID_REQUEST");
        expectError(postSource("""
                {"kind": "TEXT", "name": null}"""), 400, "INVALID_REQUEST");
        expectError(postSource("{not json"), 400, "INVALID_REQUEST");
        expectError(postSource("{\"kind\": \"TEXT\", \"name\": null, \"text\": \"" + "가".repeat(50_001) + "\"}"),
                413, "TEXT_TOO_LONG");

        // 대소문자 확장자 · 한도 딱 맞는 길이는 받는다
        postSource("{\"kind\": \"FILE\", \"name\": \"NOTE.TXT\", \"text\": \"" + "가".repeat(50_000) + "\"}")
                .andExpect(status().isAccepted());
        assertThat(sources.count()).isEqualTo(1);
        awaitAllDone();
    }

    // --- 도움 ---

    private long submitText() throws Exception {
        return submit("""
                {"kind": "TEXT", "name": null, "text": "바꿔요 회고"}""");
    }

    private long submit(String json) throws Exception {
        String body = postSource(json).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.jobId")).longValue();
    }

    private ResultActions postSource(String json) throws Exception {
        return mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // 분석은 다른 스레드에서 돈다 — 끝날 때까지 상태를 조회한다(FE 폴링과 같다)
    private String awaitDone(long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/jobs/{id}", jobId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (!"ANALYZING".equals(JsonPath.read(body, "$.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("작업 " + jobId + " 이(가) 15초 안에 끝나지 않았습니다");
    }

    private void awaitAllDone() throws Exception {
        for (AnalysisJob job : jobs.findAll()) {
            awaitDone(job.getId());
        }
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

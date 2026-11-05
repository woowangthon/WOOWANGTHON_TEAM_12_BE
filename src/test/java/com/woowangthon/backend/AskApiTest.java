package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/entrance-api.md 「POST /api/ask」 와 ai-api.md 「POST /ask」(S-29).
 * AI 서버 자리에 가짜 HTTP 서버를 띄워 응답 · 오류 · 늦은 답을 돌려준다(테스트 전용 — 앱은 진짜 AI 서버만 부른다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.sync-timeout-seconds=1")
class AskApiTest {

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
        FAKE_AI.createContext("/ask", exchange -> {
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

    static final String QUERY = "동아리에서 의견 모아 본 적 있었나?";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SourceRepository sources;

    @Autowired
    ActivityRepository activities;

    @Autowired
    ExperienceRepository experiences;

    @Autowired
    CitationRepository citations;

    MockMvc mvc;

    long appId;
    long chat;
    long retro;
    long chatCitation;
    long retroCitation;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        Source source = sources.save(new Source(TestLogin.USER_ID, SourceKind.FILE, "바꿔요-회고.md", "회고"));
        Activity app = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        appId = app.getId();
        Experience e101 = experiences.save(new Experience(app, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING), CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0));
        Experience e102 = experiences.save(new Experience(app, "매주 금요일 스프린트 회고를 했다",
                List.of(Competency.COLLABORATION), CompetenciesBy.AI, Actor.TEAM, SourceStatus.SOURCE_CONFIRMED, 1));
        chat = e101.getId();
        retro = e102.getId();
        chatCitation = citations.save(new Citation(e101, source,
                "내가 폴링을 WebSocket 으로 바꿔 해결했다.", true, 5, 5)).getId();
        retroCitation = citations.save(new Citation(e102, source, "팀은 매주 금요일 스프린트 회고를 했다.", true, 6, 6))
                .getId();
        reply = new FakeReply(200, contractExample(), 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table grounding_sentence_evidence, grounding_sentence, grounding_check, question_recommendation_evidence, question_recommendation, question_requirement, "
                + "supplement, clarification, question, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity, source_section, source");
    }

    // ai-api.md 「POST /ask」 응답 예시 — ID 는 이 테스트가 만든 경험 · 근거
    private String contractExample() {
        return """
                {"recommendations": [{"experienceId": %d,
                  "reason": "팀이 매주 회고로 의견을 모은 자리라 질문과 맞는다. 회고를 내가 이끌었는지는 자료에 없다",
                  "evidence": [{"type": "CITATION", "id": %d}]}],
                 "model": "gpt-test"}""".formatted(retro, retroCitation);
    }

    private ResultActions ask(String json) throws Exception {
        return mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content(json));
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
    void 경험_전부를_넘기고_추천에_활동_경험_근거를_붙여_바로_돌려준다() throws Exception {
        ask(textBody(QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations.length()").value(1))
                .andExpect(jsonPath("$.recommendations[0].activity.id").value(appId))
                .andExpect(jsonPath("$.recommendations[0].activity.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.recommendations[0].activity.period.end").value("2025-06"))
                .andExpect(jsonPath("$.recommendations[0].experience.id").value(retro))
                .andExpect(jsonPath("$.recommendations[0].experience.actor").value("TEAM"))
                .andExpect(jsonPath("$.recommendations[0].experience.citations[0].id").value(retroCitation))
                .andExpect(jsonPath("$.recommendations[0].reason").exists())
                .andExpect(jsonPath("$.recommendations[0].evidence[0].type").value("CITATION"))
                .andExpect(jsonPath("$.recommendations[0].evidence[0].id").value(retroCitation))
                .andExpect(jsonPath("$.recommendations[0].emphasis").doesNotExist())
                .andExpect(jsonPath("$.recommendations[0].gaps").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist());

        assertThat(RECEIVED).hasSize(1);
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.query")).isEqualTo(QUERY);
        // 매칭과 같은 모양 — 경험 전부 · 근거
        assertThat(JsonPath.<List<Integer>>read(sent, "$.experiences[*].id"))
                .containsExactly((int) chat, (int) retro);
        assertThat((String) JsonPath.read(sent, "$.experiences[1].activityName")).isEqualTo("바꿔요 — 캠퍼스 중고거래 앱");
        assertThat(JsonPath.<Integer>read(sent, "$.experiences[1].citations[0].id")).isEqualTo((int) retroCitation);
        assertThat(usedToday()).isEqualTo(1);
    }

    @Test
    void AI_가_맞는_게_없다고_하면_빈_목록이고_한도는_센다() throws Exception {
        reply = new FakeReply(200, "{\"recommendations\": []}", 0);
        ask(textBody(QUERY)).andExpect(status().isOk()).andExpect(jsonPath("$.recommendations.length()").value(0));
        assertThat(usedToday()).isEqualTo(1);
    }

    @Test
    void 경험이_0개면_AI_를_부르지_않고_빈_목록이고_한도에_세지_않는다() throws Exception {
        jdbc.execute("truncate table citation, experience, activity cascade");

        ask(textBody(QUERY)).andExpect(status().isOk()).andExpect(jsonPath("$.recommendations.length()").value(0));

        assertThat(RECEIVED).isEmpty();
        assertThat(usedToday()).isZero();
    }

    @Test
    void 한도를_다_쓰면_429_이고_AI_를_부르지_않는다() throws Exception {
        jdbc.update("update app_user set daily_analysis_limit = 1 where id = ?", TestLogin.USER_ID);
        ask(textBody(QUERY)).andExpect(status().isOk());

        expectError(ask(textBody(QUERY)), 429, "DAILY_LIMIT_EXCEEDED");
        assertThat(RECEIVED).hasSize(1);
        assertThat(usedToday()).isEqualTo(1);
    }

    @Test
    void 틀린_요청은_AI_를_부르지_않고_세지_않는다() throws Exception {
        expectError(ask(textBody("  \\n ")), 400, "EMPTY_TEXT");
        expectError(ask(textBody("")), 400, "EMPTY_TEXT");
        expectError(ask("{}"), 400, "INVALID_REQUEST");
        expectError(ask(textBody("가".repeat(2_001))), 400, "INVALID_REQUEST");
        assertThat(RECEIVED).isEmpty();
        assertThat(usedToday()).isZero();
    }

    @Test
    void 정확히_2000자는_받는다() throws Exception {
        ask(textBody("가".repeat(2_000))).andExpect(status().isOk());
        assertThat(RECEIVED).hasSize(1);
    }

    @Test
    void AI_오류는_502_AI_FAILED_이고_한도는_센다() throws Exception {
        reply = new FakeReply(502, """
                {"error": {"code": "LLM_ERROR", "message": "bad"}}""", 0);

        String body = ask(textBody(QUERY)).andExpect(status().isBadGateway()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat((String) JsonPath.read(body, "$.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(body, "$.error.message")).contains("LLM_ERROR");
        assertThat(usedToday()).isEqualTo(1);
    }

    @Test
    void 응답_규칙을_벗어나면_고쳐_쓰지_않고_AI_FAILED() throws Exception {
        // 보내지 않은 경험
        reply = new FakeReply(200, "{\"recommendations\": [{\"experienceId\": 999999, \"reason\": \"r\", \"evidence\": []}]}", 0);
        expectError(ask(textBody(QUERY)), 502, "AI_FAILED");

        // 다른 경험의 근거
        reply = new FakeReply(200, """
                {"recommendations": [{"experienceId": %d, "reason": "r", "evidence": [{"type": "CITATION", "id": %d}]}]}"""
                .formatted(retro, chatCitation), 0);
        expectError(ask(textBody(QUERY)), 502, "AI_FAILED");

        // 같은 경험 두 번 · 빈 reason · 4개 이상
        reply = new FakeReply(200, """
                {"recommendations": [{"experienceId": %d, "reason": "a", "evidence": []},
                                     {"experienceId": %d, "reason": "b", "evidence": []}]}""".formatted(retro, retro), 0);
        expectError(ask(textBody(QUERY)), 502, "AI_FAILED");
        reply = new FakeReply(200, "{\"recommendations\": [{\"experienceId\": %d, \"reason\": \" \", \"evidence\": []}]}"
                .formatted(retro), 0);
        expectError(ask(textBody(QUERY)), 502, "AI_FAILED");
        reply = new FakeReply(200, "{\"model\": \"x\"}", 0);
        expectError(ask(textBody(QUERY)), 502, "AI_FAILED");
    }

    @Test
    void 제한_시간을_넘기면_504_AI_TIMEOUT() throws Exception {
        reply = new FakeReply(200, contractExample(), 2_000);
        expectError(ask(textBody(QUERY)), 504, "AI_TIMEOUT");
    }
}

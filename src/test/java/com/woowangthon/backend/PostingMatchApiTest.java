package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.question.InterruptedQuestionCleaner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
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
 * BE#174 — question-api.md 「공고 기준 경험 찾기」 · 「POST …/posting-match/retry」 · 「Clarification.postingLineId」와
 * ai-api.md 「POST /posting-match」 · 「POST /match」 의 question.jobRole · question.postingLines.
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 작업이 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostingMatchApiTest {

    record Reply(int status, String body) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> MATCHED = new CopyOnWriteArrayList<>();
    static final List<String> POSTED = new CopyOnWriteArrayList<>();
    static volatile Function<String, Reply> postingReply;

    static final String MATCH_REPLY = """
            {"questionType":"OTHER","requirements":[{"text":"문항이 보려는 것","status":"NOT_FOUND","experienceIds":[]}],
             "recommendations":[],"clarifications":[],"model":"test-model"}""";

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        FAKE_AI.createContext("/match", exchange -> {
            MATCHED.add(read(exchange));
            send(exchange, new Reply(200, MATCH_REPLY));
        });
        FAKE_AI.createContext("/posting-match", exchange -> {
            String body = read(exchange);
            POSTED.add(body);
            send(exchange, postingReply.apply(body));
        });
        FAKE_AI.start();
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String LINES = """
            [{"section":"REQUIRED","text":"장애 경험 및 문제 해결 경험","ksa":"SKILL","readFrom":"URL"},
             {"section":"PREFERRED","text":"Spring Framework 기반의 B2C 서비스 개발 경험","ksa":null,"readFrom":"URL"},
             {"section":"DUTY","text":"대규모 실시간 트래픽 처리","ksa":"KNOWLEDGE","readFrom":"TEXT"}]""";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityRepository activities;
    @Autowired ExperienceRepository experiences;
    @Autowired InterruptedQuestionCleaner cleaner;

    MockMvc mvc;
    long chat;
    long retro;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        MATCHED.clear();
        POSTED.clear();
        Activity app = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        chat = experiences.save(new Experience(app, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING), CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0)).getId();
        retro = experiences.save(new Experience(app, "Spring 으로 API 를 만들었다",
                List.of(Competency.IMPLEMENTATION), CompetenciesBy.AI, Actor.TEAM, SourceStatus.SOURCE_CONFIRMED, 1)).getId();
        postingReply = body -> new Reply(200, contractReply(body));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table new_fact_candidate, answer_dismissed, answer, answer_draft, draft_sentence_evidence, draft_sentence, draft_omitted, draft_guideline, draft_experience, draft, grounding_sentence_evidence, grounding_sentence, grounding_check, question_recommendation_evidence, question_recommendation, question_requirement, "
                + "supplement, clarification, question, application_posting_line, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity_link, activity, source_section, source");
    }

    // 받은 줄 ID 로 계약 예시 모양을 만든다 — 첫 줄 PARTIAL(되묻기 하나) · 둘째 SUPPORTED · 셋째 NOT_FOUND
    private String contractReply(String request) {
        List<Number> ids = JsonPath.read(request, "$.lines[*].id");
        return """
                {"lines":[
                  {"id":%1$d,"status":"PARTIAL","experienceIds":[%4$d]},
                  {"id":%2$d,"status":"SUPPORTED","experienceIds":[%4$d,%5$d]},
                  {"id":%3$d,"status":"NOT_FOUND","experienceIds":[]}],
                 "clarifications":[{"lineId":%1$d,"experienceId":%4$d,"question":"장애를 처음 어떻게 알아차렸나요?"}],
                 "model":"test-model"}""".formatted(ids.get(0).longValue(), ids.get(1).longValue(), ids.get(2).longValue(),
                chat, retro);
    }

    @Test
    void 공고_줄이_있는_지원을_넣으면_공고_찾기가_돌고_줄별_판정과_되묻기가_저장된다() throws Exception {
        long id = submit("{\"organization\":\"OO 커머스\",\"jobRole\":\"BACKEND\",\"questions\":[{\"text\":\"지원 동기\"}],"
                + "\"postingLines\":" + LINES + "}");
        String done = awaitPosting(id);
        List<Number> lineIds = JsonPath.read(done, "$.postingLines[*].id");

        // 응답 — 줄마다 판정, PARTIAL 줄의 되묻기
        assertThat((String) JsonPath.read(done, "$.postingMatch.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(done, "$.postingMatch.error")).isNull();
        assertThat((List<?>) JsonPath.read(done, "$.postingMatch.lines")).hasSize(3);
        assertThat(((Number) JsonPath.read(done, "$.postingMatch.lines[0].postingLineId")).longValue())
                .isEqualTo(lineIds.get(0).longValue());
        assertThat((String) JsonPath.read(done, "$.postingMatch.lines[0].status")).isEqualTo("PARTIAL");
        assertThat(JsonPath.<List<Number>>read(done, "$.postingMatch.lines[1].experienceIds"))
                .extracting(Number::longValue).containsExactly(chat, retro);
        assertThat(JsonPath.<List<?>>read(done, "$.postingMatch.lines[2].experienceIds")).isEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.postingMatch.clarifications")).hasSize(1);
        assertThat(((Number) JsonPath.read(done, "$.postingMatch.clarifications[0].postingLineId")).longValue())
                .isEqualTo(lineIds.get(0).longValue());
        assertThat(((Number) JsonPath.read(done, "$.postingMatch.clarifications[0].experienceId")).longValue()).isEqualTo(chat);
        assertThat((Object) JsonPath.read(done, "$.postingMatch.clarifications[0].supplementId")).isNull();

        // AI 요청 — 줄은 id · section · text · ksa 만, 직무, 경험 전부
        String sent = POSTED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.jobRole")).isEqualTo("BACKEND");
        assertThat(JsonPath.<List<Number>>read(sent, "$.lines[*].id")).extracting(Number::longValue)
                .containsExactlyElementsOf(lineIds.stream().map(Number::longValue).toList());
        assertThat(JsonPath.<Map<String, Object>>read(sent, "$.lines[1]")).containsOnlyKeys("id", "section", "text", "ksa");
        assertThat((List<?>) JsonPath.read(sent, "$.experiences")).hasSize(2);

        // 문항 매칭에도 직무 · 공고 줄 전부가 맥락으로 실린다(id · readFrom 없이, 저장 순서 그대로)
        awaitQuestions(id);
        String matched = MATCHED.getFirst();
        assertThat((String) JsonPath.read(matched, "$.question.jobRole")).isEqualTo("BACKEND");
        assertThat(JsonPath.<List<String>>read(matched, "$.question.postingLines[*].text"))
                .containsExactly("장애 경험 및 문제 해결 경험", "Spring Framework 기반의 B2C 서비스 개발 경험", "대규모 실시간 트래픽 처리");
        assertThat(JsonPath.<Map<String, Object>>read(matched, "$.question.postingLines[0]"))
                .containsOnlyKeys("section", "text", "ksa");

        // 분석 횟수 — 문항 1 + 공고 찾기 1
        assertThat(usage()).isEqualTo(2);
    }

    @Test
    void 공고_되묻기에_답하면_보완이_되고_다시_찾기는_옛_판정_되묻기만_지운다() throws Exception {
        long id = submit("{\"questions\":[],\"postingLines\":" + LINES + "}");
        String done = awaitPosting(id);
        long clarificationId = ((Number) JsonPath.read(done, "$.postingMatch.clarifications[0].id")).longValue();

        String answered = mvc.perform(post("/api/clarifications/{id}/answer", clarificationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"모니터링 알림으로 알았다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.experienceId").value(chat))
                .andExpect(jsonPath("$.supplement.questionId").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        long supplementId = ((Number) JsonPath.read(answered, "$.supplement.id")).longValue();
        mvc.perform(get("/api/applications/{id}", id))
                .andExpect(jsonPath("$.postingMatch.clarifications[0].supplementId").value(supplementId));

        // 다른 사용자는 그 되묻기에 답하지 못한다
        TestLogin.mvc(context, 999999L).perform(post("/api/clarifications/{id}/answer", clarificationId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"남의 답\"}"))
                .andExpect(status().isNotFound());

        // 경험 다시 찾기 — 202 ANALYZING, 다음 찾기엔 답한 보완이 실린다
        retry(id).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.postingMatch.status").value("ANALYZING"))
                .andExpect(jsonPath("$.postingMatch.lines").isEmpty())
                .andExpect(jsonPath("$.postingMatch.clarifications").isEmpty());
        String again = awaitPosting(id);
        assertThat((String) JsonPath.read(again, "$.postingMatch.status")).isEqualTo("COMPLETED");
        assertThat(((Number) JsonPath.read(again, "$.postingMatch.clarifications[0].id")).longValue()).isNotEqualTo(clarificationId);
        assertThat(JsonPath.<List<String>>read(POSTED.getLast(), "$.experiences[0].supplements[*].answer"))
                .containsExactly("모니터링 알림으로 알았다");
        // 보완은 남고 옛 되묻기만 지워진다
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select clarification_id from supplement where id = ?", Long.class, supplementId)).isNull();
        assertThat(usage()).isEqualTo(2);
    }

    @Test
    void 계약을_어긴_응답이면_아무것도_저장하지_않고_실패() throws Exception {
        List<Function<String, String>> broken = List.of(
                // 줄 하나가 빠짐
                r -> contractReply(r).replaceFirst(",\\s*\\{\"id\":\\d+,\"status\":\"NOT_FOUND\",\"experienceIds\":\\[]}", ""),
                // 같은 줄이 두 번
                r -> {
                    List<Number> ids = JsonPath.read(r, "$.lines[*].id");
                    return contractReply(r).replace("\"id\":" + ids.get(2), "\"id\":" + ids.get(1));
                },
                // 보내지 않은 경험
                r -> contractReply(r).replace("[" + chat + "," + retro + "]", "[" + chat + ",999999]"),
                // NOT_FOUND 인데 경험이 있음
                r -> contractReply(r).replace("\"NOT_FOUND\",\"experienceIds\":[]", "\"NOT_FOUND\",\"experienceIds\":[" + chat + "]"),
                // 목록 밖 판정
                r -> contractReply(r).replace("\"SUPPORTED\"", "\"MAYBE\""),
                // SUPPORTED 줄에 되묻기
                r -> contractReply(r).replace("\"PARTIAL\"", "\"SUPPORTED\""),
                // 되묻기의 경험이 그 줄의 경험이 아님
                r -> contractReply(r).replace("\"experienceId\":" + chat, "\"experienceId\":" + retro),
                // 빈 질문
                r -> contractReply(r).replace("장애를 처음 어떻게 알아차렸나요?", " "));
        for (Function<String, String> reply : broken) {
            postingReply = body -> new Reply(200, reply.apply(body));
            long id = submit("{\"questions\":[],\"postingLines\":" + LINES + "}");
            String failed = awaitPosting(id);
            assertThat((String) JsonPath.read(failed, "$.postingMatch.status")).isEqualTo("FAILED");
            assertThat((String) JsonPath.read(failed, "$.postingMatch.error.code")).isEqualTo("AI_FAILED");
            assertThat((List<?>) JsonPath.read(failed, "$.postingMatch.lines")).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from application_posting_line where match_status is not null",
                Integer.class)).isZero();
    }

    @Test
    void AI_오류면_실패가_남고_다시_시도_규칙대로_받는다() throws Exception {
        postingReply = body -> new Reply(502, "{\"error\":{\"code\":\"LLM_ERROR\",\"message\":\"x\"}}");
        long id = submit("{\"questions\":[],\"postingLines\":" + LINES + "}");
        String failed = awaitPosting(id);
        assertThat((String) JsonPath.read(failed, "$.postingMatch.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.postingMatch.error.code")).isNotBlank();

        // 남의 지원 404 · 줄 없는 지원 409 · 도는 중 409 · 한도 429 — 모두 아무것도 바꾸지 않는다
        TestLogin.mvc(context, 999999L).perform(post("/api/applications/{id}/posting-match/retry", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("APPLICATION_NOT_FOUND"));
        long noLines = submit("{\"questions\":[{\"text\":\"지원 동기\"}]}");
        retry(noLines).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POSTING_NOT_FOUND"));
        jdbc.update("update application set posting_match_status = 'ANALYZING' where id = ?", id);
        retry(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POSTING_MATCH_ANALYZING"));
        jdbc.update("update application set posting_match_status = 'FAILED' where id = ?", id);
        jdbc.update("update app_user set daily_analysis_limit = ? where id = ?", usage(), TestLogin.USER_ID);
        retry(id).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/applications/{id}", id)).andExpect(jsonPath("$.postingMatch.status").value("FAILED"));
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);

        postingReply = body -> new Reply(200, contractReply(body));
        retry(id).andExpect(status().isAccepted());
        String done = awaitPosting(id);
        assertThat((String) JsonPath.read(done, "$.postingMatch.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(done, "$.postingMatch.error")).isNull();
    }

    @Test
    void 그새_지워진_경험은_줄과_되묻기에서_빠지고_판정은_그대로다() throws Exception {
        long id = submit("{\"questions\":[],\"postingLines\":" + LINES + "}");
        awaitPosting(id);
        jdbc.update("delete from experience where id = ?", chat);
        String after = mvc.perform(get("/api/applications/{id}", id)).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(after, "$.postingMatch.lines[0].status")).isEqualTo("PARTIAL");
        assertThat(JsonPath.<List<?>>read(after, "$.postingMatch.lines[0].experienceIds")).isEmpty();
        assertThat(JsonPath.<List<Number>>read(after, "$.postingMatch.lines[1].experienceIds"))
                .extracting(Number::longValue).containsExactly(retro);
        assertThat(JsonPath.<List<?>>read(after, "$.postingMatch.clarifications")).isEmpty();
    }

    @Test
    void 문항_0개_지원에_문항을_더하면_공고_줄이_실리고_직무가_없으면_칸이_없다() throws Exception {
        long id = submit("{\"questions\":[],\"postingLines\":" + LINES + "}");
        awaitPosting(id);
        assertThat(MATCHED).isEmpty();
        assertThat((Object) JsonPath.read(POSTED.getFirst(), "$.jobRole")).isNull();

        mvc.perform(post("/api/applications/{id}/questions", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"문제 해결 경험\"}")).andExpect(status().isAccepted());
        awaitQuestions(id);
        String matched = MATCHED.getFirst();
        assertThat(JsonPath.<Map<String, Object>>read(matched, "$.question")).doesNotContainKey("jobRole");
        assertThat((List<?>) JsonPath.read(matched, "$.question.postingLines")).hasSize(3);
        // 공고 기준 경험 찾기는 다시 돌지 않는다
        assertThat(POSTED).hasSize(1);
    }

    @Test
    void 공고_줄이_없는_지원은_공고_찾기가_없고_매칭엔_직무만_실린다() throws Exception {
        long id = submit("{\"jobRole\":\"BACKEND\",\"questions\":[{\"text\":\"지원 동기\"}]}");
        awaitQuestions(id);
        mvc.perform(get("/api/applications/{id}", id)).andExpect(jsonPath("$.postingMatch").value(nullValue()));
        String matched = MATCHED.getFirst();
        assertThat((String) JsonPath.read(matched, "$.question.jobRole")).isEqualTo("BACKEND");
        assertThat(JsonPath.<Map<String, Object>>read(matched, "$.question")).doesNotContainKey("postingLines");
        assertThat(POSTED).isEmpty();
        assertThat(usage()).isEqualTo(1);
    }

    @Test
    void 서버가_다시_켜지면_돌던_공고_찾기는_실패가_된다() throws Exception {
        postingReply = body -> new Reply(502, "{\"error\":{\"code\":\"LLM_ERROR\",\"message\":\"x\"}}");
        long id = submit("{\"questions\":[],\"postingLines\":" + LINES + "}");
        awaitPosting(id);
        jdbc.update("update application set posting_match_status = 'ANALYZING', posting_match_error_code = null where id = ?", id);

        cleaner.run(null);

        mvc.perform(get("/api/applications/{id}", id))
                .andExpect(jsonPath("$.postingMatch.status").value("FAILED"))
                .andExpect(jsonPath("$.postingMatch.error.code").value("INTERNAL_ERROR"));
    }

    private long submit(String json) throws Exception {
        String body = mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.applicationId")).longValue();
    }

    private ResultActions retry(long id) throws Exception {
        return mvc.perform(post("/api/applications/{id}/posting-match/retry", id));
    }

    private int usage() {
        return jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage where user_id = ?", Integer.class,
                TestLogin.USER_ID);
    }

    private String awaitPosting(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/applications/{id}", id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (!"ANALYZING".equals(JsonPath.read(body, "$.postingMatch.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("지원 " + id + " 의 공고 기준 경험 찾기가 15초 안에 끝나지 않았습니다");
    }

    private void awaitQuestions(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/applications/{id}", id)).andReturn().getResponse().getContentAsString();
            if (!JsonPath.<List<String>>read(body, "$.questions[*].status").contains("ANALYZING")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("지원 " + id + " 의 문항 추천이 15초 안에 끝나지 않았습니다");
    }
}

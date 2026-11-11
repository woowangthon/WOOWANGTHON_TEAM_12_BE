package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.question.MatchRunner;
import com.woowangthon.backend.question.QuestionService;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ETC docs/contracts/mcp-api.md 「create_application — 지원 만들기 (외부)」 · 「get_question」 · 「변경 기록」(S-55, ADR-011, BE#93).
 * 시드를 켜고 테스트 계정으로 돌린다. 추천 자리는 목이라 결과는 QuestionService 로 바로 싣고, 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class McpApplicationApiTest {

    private static final String ONE_QUESTION = """
            {"organization":"OO 백엔드 부트캠프","questions":[{"text":"문제를 해결한 경험을 적어 주세요."}]}""";
    private static final String TWO_QUESTIONS = """
            {"organization":"OO 백엔드 부트캠프","organizationType":"BOOTCAMP","jobRole":"BACKEND",
             "questions":[{"text":"첫 문항"},{"text":"둘째 문항"}]}""";

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    McpConnectionService connections;

    @Autowired
    QuestionService questions;

    @Autowired
    UsageLimiter limiter;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    EntityManager em;

    @MockitoBean
    MatchRunner matches;

    MockMvc web;
    MockMvc client;
    long userId;
    String token;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        web = TestLogin.mvc(context, userId);
        client = MockMvcBuilders.webAppContextSetup(context).build();
        token = tokenOf(connections.create(userId).url());
    }

    // --- 범위 ---

    @Test
    void 쓰기를_켜야_두_도구가_보이고_READ_는_없는_도구로_답한다() throws Exception {
        rpc(token, "tools/list", "{}").andExpect(jsonPath("$.result.tools[*].name",
                containsInAnyOrder("search_experiences", "list_activities", "get_activity")));
        call(token, "create_application", ONE_QUESTION).andExpect(jsonPath("$.error.code").value(-32602));
        call(token, "get_question", "{\"questionId\":1}").andExpect(jsonPath("$.error.code").value(-32602));

        connections.setWriteEnabled(userId, true);

        rpc(token, "tools/list", "{}")
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("create_application")))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("get_question")))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'create_application')].description",
                        hasItem(not(containsString("confirm")))))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'create_application')].annotations.destructiveHint",
                        hasItem(false)))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'get_question')].annotations.readOnlyHint", hasItem(true)));
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\"}")
                .andExpect(jsonPath("$.result.instructions", containsString("create_application 의 문항은")))
                .andExpect(jsonPath("$.result.instructions", containsString("get_question 의 status")));
    }

    @Test
    void 차곡이는_도구가_그대로다_get_question_은_없고_지원_만들기는_CONFIRM_REQUIRED() throws Exception {
        String chagok = tokenOf(connections.createChagok(userId).url());

        rpc(chagok, "tools/list", "{}")
                .andExpect(jsonPath("$.result.tools[*].name", containsInAnyOrder("search_experiences", "list_activities",
                        "get_activity", "create_application", "add_source")))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'create_application')].description",
                        hasItem(containsString("confirm"))));
        call(chagok, "get_question", "{\"questionId\":1}").andExpect(jsonPath("$.error.code").value(-32602));
        assertToolError(call(chagok, "create_application", ONE_QUESTION), "CONFIRM_REQUIRED");
        assertThat(count("mcp_change")).isZero();
    }

    // --- create_application ---

    @Test
    void 바로_저장하고_문항마다_추천을_시작하고_변경_기록을_남긴다() throws Exception {
        connections.setWriteEnabled(userId, true);
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"claude-ai\"}}").andExpect(status().isOk());
        int usedBefore = used();

        JsonNode result = toolResult(call(token, "create_application", TWO_QUESTIONS));

        long applicationId = result.get("applicationId").asLong();
        long first = result.get("questionIds").get(0).asLong();
        long second = result.get("questionIds").get(1).asLong();
        assertThat(result.get("status").asString()).isEqualTo("ANALYZING");
        assertThat(result.get("notice").asString()).startsWith("추천을 만드는 중이에요");
        verify(matches).run(first);
        verify(matches).run(second);
        em.flush();
        assertThat(jdbc.queryForMap("select type, client, status, application_id, organization, question_count, reason "
                + "from mcp_change where id = ?", result.get("changeId").asLong()))
                .containsEntry("type", "CREATE_APPLICATION").containsEntry("client", "claude-ai")
                .containsEntry("status", "APPLIED").containsEntry("application_id", applicationId)
                .containsEntry("organization", "OO 백엔드 부트캠프").containsEntry("question_count", 2)
                .containsEntry("reason", null);
        assertThat(used()).isEqualTo(usedBefore + 2);

        em.clear();
        web.perform(get("/api/applications/{id}", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.organizationType").value("BOOTCAMP"))
                .andExpect(jsonPath("$.jobRole").value("BACKEND"))
                .andExpect(jsonPath("$.questions[0].text").value("첫 문항"))
                .andExpect(jsonPath("$.questions[1].text").value("둘째 문항"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 추천은_지원과_변경_기록이_커밋된_뒤_시작한다() throws Exception {
        AtomicLong recorded = new AtomicLong(-1);
        doAnswer(invocation -> {
            // 다른 연결에서 읽는다 — 커밋되지 않았으면 0
            recorded.set(jdbc.queryForObject("""
                    select count(*) from mcp_change c join question q on q.application_id = c.application_id where q.id = ?
                    """, Long.class, invocation.<Long>getArgument(0)));
            return null;
        }).when(matches).run(anyLong());
        try {
            connections.setWriteEnabled(userId, true);

            toolResult(call(token, "create_application", ONE_QUESTION));

            assertThat(recorded.get()).isEqualTo(1);
        } finally {
            jdbc.update("""
                    delete from question where application_id in (select application_id from mcp_change where user_id = ?)
                    """, userId);
            jdbc.update("delete from application where id in (select application_id from mcp_change where user_id = ?)", userId);
            jdbc.update("delete from mcp_change where user_id = ?", userId);
            jdbc.update("delete from analysis_usage where user_id = ?", userId);
            connections.delete(userId);
        }
    }

    @Test
    void 웹과_같은_검사에_걸리면_아무것도_남기지_않는다() throws Exception {
        connections.setWriteEnabled(userId, true);
        long applications = count("application");
        int usedBefore = used();
        List<Map<String, String>> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Map.of("text", "문항"));
        }

        assertToolError(call(token, "create_application", "{\"organization\":\"OO\",\"questions\":[]}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "create_application", "{\"organization\":\"OO\"}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "create_application", "{\"questions\":\"문항\"}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "create_application", json.writeValueAsString(Map.of("questions", eleven))), "INVALID_ARGUMENT");
        assertToolError(call(token, "create_application", "{\"questions\":[{\"text\":\"문항\"}],\"jobRole\":\"목록밖\"}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "create_application", "{\"questions\":[{\"text\":\"  \"}]}"), "EMPTY_TEXT");
        assertToolError(call(token, "create_application",
                json.writeValueAsString(Map.of("questions", List.of(Map.of("text", "가".repeat(5001)))))), "TEXT_TOO_LONG");

        assertThat(count("application")).isEqualTo(applications);
        assertThat(count("mcp_change")).isZero();
        assertThat(used()).isEqualTo(usedBefore);
        verify(matches, never()).run(anyLong());
    }

    @Test
    void 남은_분석_횟수가_문항_수보다_적으면_아무것도_저장하지_않는다() throws Exception {
        connections.setWriteEnabled(userId, true);
        int limit = limiter.today(userId).limit();
        jdbc.update("insert into analysis_usage (user_id, day, count) values (?, ?, ?) "
                + "on conflict (user_id, day) do update set count = excluded.count",
                userId, Date.valueOf(LocalDate.now(Times.SEOUL)), limit - 1);
        long applications = count("application");

        assertToolError(call(token, "create_application", TWO_QUESTIONS), "DAILY_LIMIT_EXCEEDED");

        assertThat(count("application")).isEqualTo(applications);
        assertThat(count("mcp_change")).isZero();
        assertThat(used()).isEqualTo(limit - 1);
        verify(matches, never()).run(anyLong());
    }

    // --- get_question ---

    @Test
    void 분석_중이면_비어_있고_끝나면_추천을_읽고_draft_answer_쓰는_중인_답은_싣지_않는다() throws Exception {
        Created created = create(ONE_QUESTION);
        long questionId = created.firstQuestion();

        JsonNode analyzing = toolResult(call(token, "get_question", "{\"questionId\":" + questionId + "}"));
        assertThat(analyzing.get("status").asString()).isEqualTo("ANALYZING");
        assertThat(analyzing.get("recommendations")).isEmpty();
        assertThat(analyzing.has("draft")).isFalse();
        assertThat(analyzing.has("answer")).isFalse();

        finish(questionId);
        // 웹에서 쓰는 중인 답(S-72)은 외부 AI 로 나가지 않는다(ETC#316)
        web.perform(put("/api/questions/{id}/answer-draft", questionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"웹에서 쓰는 중인 답\"}")).andExpect(status().isOk());

        JsonNode done = toolResult(call(token, "get_question", "{\"questionId\":\"" + questionId + "\"}"));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("requirements").get(0).get("status").asString()).isEqualTo("SUPPORTED");
        assertThat(done.get("recommendations").get(0).get("reason").asString()).isEqualTo("맞는 경험이다");
        assertThat(done.get("recommendations").get(0).get("experience").get("content").asString()).isNotBlank();
        assertThat(done.get("clarifications").get(0).get("question").asString()).isEqualTo("어떻게 확인했나요?");
        assertThat(done.has("draft")).isFalse();
        assertThat(done.has("answer")).isFalse();
        assertThat(done.has("answerDraft")).isFalse();
        assertThat(done.toString()).doesNotContain("웹에서 쓰는 중인 답");

        // 나머지는 GET /api/questions/{id} 와 같다
        JsonNode api = json.readTree(web.perform(get("/api/questions/{id}", questionId)).andReturn().getResponse()
                .getContentAsString());
        assertThat(api.get("answerDraft").get("text").asString()).isEqualTo("웹에서 쓰는 중인 답");
        ((ObjectNode) api).remove(List.of("draft", "answer", "answerDraft"));
        assertThat(done).isEqualTo(api);
    }

    @Test
    void 분석이_실패하면_FAILED_와_error_가_보이고_추천을_채우지_않는다() throws Exception {
        Created created = create(ONE_QUESTION);
        questions.fail(created.firstQuestion(), "AI_FAILED", "AI 서버가 응답하지 않습니다");
        em.flush();
        em.clear();

        JsonNode failed = toolResult(call(token, "get_question", "{\"questionId\":" + created.firstQuestion() + "}"));

        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").get("code").asString()).isEqualTo("AI_FAILED");
        assertThat(failed.get("requirements")).isEmpty();
        assertThat(failed.get("recommendations")).isEmpty();
        assertThat(failed.get("clarifications")).isEmpty();
    }

    @Test
    void 남의_문항이나_없는_문항은_QUESTION_NOT_FOUND_이고_입력이_틀리면_INVALID_ARGUMENT() throws Exception {
        Created created = create(ONE_QUESTION);
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        String otherToken = tokenOf(connections.create(otherId).url());
        connections.setWriteEnabled(otherId, true);

        assertToolError(call(otherToken, "get_question", "{\"questionId\":" + created.firstQuestion() + "}"), "QUESTION_NOT_FOUND");
        assertToolError(call(token, "get_question", "{\"questionId\":999999}"), "QUESTION_NOT_FOUND");
        assertToolError(call(token, "get_question", "{}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "get_question", "{\"questionId\":\"하나\"}"), "INVALID_ARGUMENT");
    }

    // --- 변경 기록 · 되돌리기 ---

    @Test
    void 분석_중이면_되돌릴_수_없고_끝나면_지운다() throws Exception {
        Created created = create(TWO_QUESTIONS);
        finish(created.firstQuestion());

        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].type").value("CREATE_APPLICATION"))
                .andExpect(jsonPath("$.changes[0].undoable").value(false))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value("ANALYZING"))
                .andExpect(jsonPath("$.changes[0].application.id").value(created.applicationId()))
                .andExpect(jsonPath("$.changes[0].application.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.changes[0].application.questionCount").value(2))
                .andExpect(jsonPath("$.changes[0].application.analyzingCount").value(1))
                .andExpect(jsonPath("$.changes[0].experience").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].source").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].supplement").value(nullValue()));
        undo(created.changeId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));

        finish(created.questionIds().get(1));
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].undoable").value(true))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value(nullValue()));
        int usedBefore = used();

        undo(created.changeId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("UNDONE"))
                .andExpect(jsonPath("$.change.undoBlockedReason").value("DECIDED"))
                .andExpect(jsonPath("$.change.application.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.change.application.questionCount").value(2))
                .andExpect(jsonPath("$.change.application.analyzingCount").value(0))
                .andExpect(jsonPath("$.removedExperiences").value(0))
                .andExpect(jsonPath("$.keptExperiences").value(0));

        em.flush();
        em.clear();
        assertThat(jdbc.queryForObject("select count(*) from application where id = ?", Long.class,
                created.applicationId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from question where application_id = ?", Long.class,
                created.applicationId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from clarification where question_id = ?", Long.class,
                created.firstQuestion())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from question_recommendation where question_id = ?", Long.class,
                created.firstQuestion())).isZero();
        assertThat(used()).isEqualTo(usedBefore); // 쓴 횟수는 돌아오지 않는다
        web.perform(get("/api/applications/{id}", created.applicationId())).andExpect(status().isNotFound());
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].status").value("UNDONE"))
                .andExpect(jsonPath("$.changes[0].application.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.changes[0].application.questionCount").value(2));

        undo(created.changeId()).andExpect(status().isConflict());
    }

    @Test
    void 분석이_실패한_지원도_되돌릴_수_있다() throws Exception {
        Created created = create(ONE_QUESTION);
        questions.fail(created.firstQuestion(), "AI_FAILED", "실패");
        em.flush();
        em.clear();

        undo(created.changeId()).andExpect(status().isOk()).andExpect(jsonPath("$.change.status").value("UNDONE"));
    }

    @Test
    void 지원_만들기에는_맞아요가_없다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());

        web.perform(post("/api/mcp/changes/{id}/confirm", created.changeId())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_CONFIRMABLE"));
    }

    @Test
    void 되묻기에_답하면_되돌릴_수_없고_보완을_지우면_다시_된다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());
        long clarification = jdbc.queryForObject("select id from clarification where question_id = ?", Long.class,
                created.firstQuestion());

        String saved = web.perform(post("/api/clarifications/{id}/answer", clarification)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"로그로 확인했다\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        em.flush();
        em.clear();

        assertChangedAfter(created);

        web.perform(delete(
                "/api/supplements/{id}", json.readTree(saved).get("supplement").get("id").asLong()))
                .andExpect(status().is2xxSuccessful());
        em.flush();
        em.clear();
        undo(created.changeId()).andExpect(status().isOk());
    }

    @Test
    void 초안이_있으면_되돌릴_수_없다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());
        jdbc.update("insert into draft (question_id, status) values (?, 'FAILED')", created.firstQuestion());

        assertChangedAfter(created);
    }

    @Test
    void 근거_점검이_있으면_되돌릴_수_없다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());
        jdbc.update("insert into grounding_check (question_id, text, status) values (?, '글', 'FAILED')", created.firstQuestion());

        assertChangedAfter(created);
    }

    @Test
    void 확정_답변이_있으면_되돌릴_수_없다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());
        jdbc.update("insert into answer (question_id, text, confirmed_at) values (?, '답', now())", created.firstQuestion());

        assertChangedAfter(created);
    }

    @Test
    void 지원_상태를_바꾸면_되돌릴_수_없다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());
        web.perform(patch("/api/applications/{id}", created.applicationId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicationStatus\":\"SUBMITTED\"}")).andExpect(status().isOk());
        em.flush();
        em.clear();

        assertChangedAfter(created);
    }

    @Test
    void 문항을_더하면_되돌릴_수_없다() throws Exception {
        Created created = create(ONE_QUESTION);
        finish(created.firstQuestion());
        web.perform(post("/api/applications/{id}/questions", created.applicationId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"더한 문항\"}")).andExpect(status().is2xxSuccessful());
        em.flush();
        em.clear();

        // 더한 문항이 분석 중이어도 풀리지 않는 이유를 먼저 보인다
        assertChangedAfter(created);
    }

    // --- 도우미 ---

    private record Created(long changeId, long applicationId, List<Long> questionIds) {

        long firstQuestion() {
            return questionIds.getFirst();
        }
    }

    private Created create(String arguments) throws Exception {
        connections.setWriteEnabled(userId, true);
        JsonNode result = toolResult(call(token, "create_application", arguments));
        em.flush();
        em.clear();
        List<Long> ids = new ArrayList<>();
        result.get("questionIds").forEach(id -> ids.add(id.asLong()));
        return new Created(result.get("changeId").asLong(), result.get("applicationId").asLong(), ids);
    }

    // AI 결과를 그대로 싣는다 — 근거가 있는 경험 하나를 추천하고 되묻는다
    private void finish(long questionId) {
        MatchRequest sent = questions.matchRequest(questionId);
        MatchRequest.ExperiencePart experience = sent.experiences().stream()
                .filter(e -> !e.citations().isEmpty()).findFirst().orElseThrow();
        questions.complete(questionId, sent, new MatchResponse("PROBLEM_SOLVING",
                List.of(new MatchResponse.RequirementResult("문제 해결", "SUPPORTED", List.of(experience.id()))),
                List.of(new MatchResponse.RecommendationResult(experience.id(), "맞는 경험이다", "해결 과정을 강조한다",
                        List.of(new MatchResponse.EvidenceResult("CITATION", experience.citations().getFirst().id())),
                        List.of())),
                List.of(new MatchResponse.ClarificationResult(experience.id(), "어떻게 확인했나요?")), "test-model"));
        em.flush();
        em.clear();
    }

    private void assertChangedAfter(Created created) throws Exception {
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].undoable").value(false))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value("CHANGED_AFTER"));
        undo(created.changeId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));
        em.flush();
        assertThat(jdbc.queryForObject("select count(*) from application where id = ?", Long.class,
                created.applicationId())).isEqualTo(1);
    }

    private ResultActions undo(long changeId) throws Exception {
        return web.perform(post("/api/mcp/changes/{id}/undo", changeId));
    }

    private long count(String table) {
        em.flush();
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private int used() {
        em.flush();
        List<Integer> used = jdbc.queryForList("select count from analysis_usage where user_id = ?", Integer.class, userId);
        return used.isEmpty() ? 0 : used.getFirst();
    }

    private ResultActions rpc(String token, String method, String params) throws Exception {
        return client.perform(post("/mcp/{token}", token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/json, text/event-stream")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}"));
    }

    private ResultActions call(String token, String tool, String arguments) throws Exception {
        return rpc(token, "tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
    }

    private JsonNode toolResult(ResultActions actions) throws Exception {
        JsonNode result = json.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("result");
        assertThat(result.get("isError").asBoolean()).as(result.toString()).isFalse();
        return result.get("structuredContent");
    }

    private void assertToolError(ResultActions actions, String code) throws Exception {
        JsonNode result = json.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("result");
        assertThat(result.get("isError").asBoolean()).as(result.toString()).isTrue();
        assertThat(json.readTree(result.get("content").get(0).get("text").asString()).get("error").get("code").asString())
                .isEqualTo(code);
    }

    private static String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}

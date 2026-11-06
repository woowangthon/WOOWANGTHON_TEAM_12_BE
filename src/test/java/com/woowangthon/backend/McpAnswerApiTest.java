package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.question.MatchRunner;
import com.woowangthon.backend.question.QuestionService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ETC docs/contracts/mcp-api.md 「answer_clarification」 · 「변경 기록」(S-56, ADR-011, BE#94).
 * 시드를 켜고 테스트 계정으로 돌린다. 추천 자리는 목이라 결과는 QuestionService 로 바로 싣고, 테스트마다 롤백한다.
 * 초안 · 근거 점검 요청에 실리지 않는 것은 DraftApiTest · GroundingCheckApiTest 가 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class McpAnswerApiTest {

    private static final String ANSWER = "로그에 도착 시간을 남겨 지연이 1초 안인지 확인했다";

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    McpConnectionService connections;

    @Autowired
    QuestionService questions;

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

    long applicationChange;
    long applicationId;
    long questionId;
    long clarificationId;
    long experienceId;

    @BeforeEach
    void setUp() throws Exception {
        userId = users.findByLoginId("test").orElseThrow().getId();
        web = TestLogin.mvc(context, userId);
        client = MockMvcBuilders.webAppContextSetup(context).build();
        token = tokenOf(connections.create(userId).url());
    }

    // --- 범위 ---

    @Test
    void 쓰기를_켠_연결에만_보이고_바로_실행한다() throws Exception {
        rpc(token, "tools/list", "{}").andExpect(jsonPath("$.result.tools[*].name", not(hasItem("answer_clarification"))));
        call(token, "answer_clarification", "{}").andExpect(jsonPath("$.error.code").value(-32602));
        String chagok = tokenOf(connections.createChagok(userId).url());
        rpc(chagok, "tools/list", "{}").andExpect(jsonPath("$.result.tools[*].name", not(hasItem("answer_clarification"))));
        call(chagok, "answer_clarification", "{}").andExpect(jsonPath("$.error.code").value(-32602));

        connections.setWriteEnabled(userId, true);

        rpc(token, "tools/list", "{}")
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("answer_clarification")))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'answer_clarification')].annotations.readOnlyHint", hasItem(false)))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'answer_clarification')].annotations.destructiveHint", hasItem(false)));
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\"}")
                .andExpect(jsonPath("$.result.instructions", containsString("answer_clarification 의 answer 는")))
                .andExpect(jsonPath("$.result.instructions", containsString("mcpChange 가 있는 보완은")));
    }

    // --- 답 전하기 ---

    @Test
    void 보완으로_저장하고_mcpChange_와_변경_기록을_남기고_경험은_그대로다() throws Exception {
        prepare();
        Map<String, Object> before = jdbc.queryForMap("select content, source_status from experience where id = ?", experienceId);
        int analysesBefore = jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage where user_id = ?",
                Integer.class, userId);

        JsonNode result = answer();

        long changeId = result.get("changeId").asLong();
        long supplementId = result.get("supplementId").asLong();
        assertThat(result.get("experienceId").asLong()).isEqualTo(experienceId);
        assertThat(jdbc.queryForMap("select content, source_status from experience where id = ?", experienceId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage where user_id = ?", Integer.class, userId))
                .isEqualTo(analysesBefore);

        activity().andExpect(jsonPath(supplement(supplementId) + ".mcpChange.changeId", hasItem((int) changeId)))
                .andExpect(jsonPath(supplement(supplementId) + ".mcpChange.client", hasItem("외부 AI")))
                .andExpect(jsonPath(supplement(supplementId) + ".answer", hasItem(ANSWER)));
        web.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.clarifications[0].supplementId").value(supplementId));
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].id").value(changeId))
                .andExpect(jsonPath("$.changes[0].type").value("ANSWER_CLARIFICATION"))
                .andExpect(jsonPath("$.changes[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.changes[0].undoable").value(true))
                .andExpect(jsonPath("$.changes[0].experience").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].application").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].supplement.id").value(supplementId))
                .andExpect(jsonPath("$.changes[0].supplement.experienceId").value(experienceId))
                .andExpect(jsonPath("$.changes[0].supplement.activityName").isString())
                .andExpect(jsonPath("$.changes[0].supplement.question").value("어떻게 확인했나요?"))
                .andExpect(jsonPath("$.changes[0].supplement.answer").value(ANSWER));
    }

    @Test
    void 웹에서_답한_보완은_mcpChange_가_null() throws Exception {
        prepare();
        web.perform(post("/api/clarifications/{id}/answer", clarificationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"웹에서 답함\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.supplement.mcpChange").value(nullValue()));
    }

    @Test
    void MCP_로_읽어도_mcpChange_와_함께_보인다() throws Exception {
        prepare();
        long changeId = answer().get("changeId").asLong();

        JsonNode found = toolResult(call(token, "search_experiences", "{\"query\":\"지연이\"}"));

        assertThat(found.get("experiences").get(0).get("supplements").get(0).get("mcpChange").get("changeId").asLong())
                .isEqualTo(changeId);
    }

    // --- 근거 ---

    @Test
    void 확인_전_답은_match_ask_요청에_실리지_않고_맞아요_뒤에_실린다() throws Exception {
        prepare();
        JsonNode result = answer();
        assertThat(sentSupplements()).doesNotContain(ANSWER);
        assertThat(askedSupplements()).doesNotContain(ANSWER);

        confirm(result.get("changeId").asLong()).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.change.undoable").value(false))
                .andExpect(jsonPath("$.change.undoBlockedReason").value("DECIDED"));
        settle();

        assertThat(sentSupplements()).contains(ANSWER);
        assertThat(askedSupplements()).contains(ANSWER);
        activity().andExpect(jsonPath(supplement(result.get("supplementId").asLong()) + ".mcpChange", hasItem(nullValue())));
    }

    @Test
    void 확인_전_답을_evidence_로_고르면_거부해_끝난_추천에_남지_않는다() throws Exception {
        prepare();
        long supplementId = answer().get("supplementId").asLong();
        long next = createQuestion();
        MatchRequest sent = questions.matchRequest(next);

        assertThatThrownBy(() -> questions.complete(next, sent, new MatchResponse("PROBLEM_SOLVING", List.of(),
                List.of(new MatchResponse.RecommendationResult(experienceId, "이유", "강조",
                        List.of(new MatchResponse.EvidenceResult("SUPPLEMENT", supplementId)), List.of())),
                List.of(), "test-model")))
                .isInstanceOf(AiCallException.class);
        em.clear();

        assertThat(jdbc.queryForObject("select count(*) from question_recommendation_evidence where type = 'SUPPLEMENT' "
                + "and ref_id = ?", Long.class, supplementId)).isZero();
    }

    // --- 되돌리기 · 고치기 ---

    @Test
    void 되돌리면_보완이_지워지고_그_되묻기에_다시_답한다() throws Exception {
        prepare();
        JsonNode result = answer();
        long changeId = result.get("changeId").asLong();

        web.perform(post("/api/mcp/changes/{id}/undo", changeId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("UNDONE"))
                .andExpect(jsonPath("$.change.supplement.answer").value(ANSWER))
                .andExpect(jsonPath("$.removedExperiences").value(0))
                .andExpect(jsonPath("$.keptExperiences").value(0));
        settle();

        assertThat(jdbc.queryForObject("select count(*) from supplement where id = ?", Long.class,
                result.get("supplementId").asLong())).isZero();
        web.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.clarifications[0].supplementId").value(nullValue()));
        web.perform(post("/api/mcp/changes/{id}/undo", changeId)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));
        settle();

        JsonNode again = answer();
        assertThat(again.get("supplementId").asLong()).isNotEqualTo(result.get("supplementId").asLong());
    }

    @Test
    void 맞아요_뒤엔_되돌릴_수_없고_다시_눌러도_409() throws Exception {
        prepare();
        long changeId = answer().get("changeId").asLong();
        confirm(changeId).andExpect(status().isOk());
        settle();

        confirm(changeId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_CONFIRMABLE"));
        web.perform(post("/api/mcp/changes/{id}/undo", changeId)).andExpect(status().isConflict());
    }

    @Test
    void 웹에서_답을_고치면_사용자_말이_되고_변경은_CHANGED_AFTER() throws Exception {
        prepare();
        JsonNode result = answer();
        long changeId = result.get("changeId").asLong();

        web.perform(patch("/api/supplements/{id}", result.get("supplementId").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"내가 다시 쓴 답\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpChange").value(nullValue()));
        settle();

        assertThat(sentSupplements()).contains("내가 다시 쓴 답");
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.changes[0].undoable").value(false))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value("CHANGED_AFTER"))
                .andExpect(jsonPath("$.changes[0].supplement.answer").value(ANSWER));
        confirm(changeId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_CONFIRMABLE"));
        web.perform(post("/api/mcp/changes/{id}/undo", changeId)).andExpect(status().isConflict());
    }

    @Test
    void 웹에서_보완을_지우면_변경은_CHANGED_AFTER() throws Exception {
        prepare();
        JsonNode result = answer();

        web.perform(delete("/api/supplements/{id}", result.get("supplementId").asLong())).andExpect(status().is2xxSuccessful());
        settle();

        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].undoable").value(false))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value("CHANGED_AFTER"));
    }

    @Test
    void 지원_되돌리기는_이_답도_세고_답을_되돌리면_다시_된다() throws Exception {
        prepare();
        long changeId = answer().get("changeId").asLong();

        web.perform(post("/api/mcp/changes/{id}/undo", applicationChange)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));
        settle();

        web.perform(post("/api/mcp/changes/{id}/undo", changeId)).andExpect(status().isOk());
        settle();
        web.perform(post("/api/mcp/changes/{id}/undo", applicationChange)).andExpect(status().isOk());
        settle();
        assertThat(jdbc.queryForObject("select count(*) from application where id = ?", Long.class, applicationId)).isZero();
    }

    @Test
    void 같은_경험에_답이_끼어도_고치기를_무르면_앞선_고치기가_다시_정할_변경이_된다() throws Exception {
        prepare();
        String a = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);
        long first = toolResult(call(token, "update_experience", rewrite(a, "둘째 본문"))).get("changeId").asLong();
        settle();
        answer();
        long third = toolResult(call(token, "update_experience", rewrite("둘째 본문", "셋째 본문"))).get("changeId").asLong();
        settle();

        web.perform(post("/api/mcp/changes/{id}/undo", third)).andExpect(status().isOk());
        settle();

        assertThat(jdbc.queryForObject("select mcp_change_id from experience where id = ?", Long.class, experienceId))
                .isEqualTo(first);
    }

    // --- 오류 ---

    @Test
    void 이미_답한_되묻기는_CLARIFICATION_ALREADY_ANSWERED_이고_변경을_남기지_않는다() throws Exception {
        prepare();
        answer();
        long before = count();

        assertToolError(call(token, "answer_clarification", arguments(clarificationId, "또")), "CLARIFICATION_ALREADY_ANSWERED");

        em.clear();
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void 남의_되묻기나_없는_되묻기는_CLARIFICATION_NOT_FOUND() throws Exception {
        prepare();
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        String otherToken = tokenOf(connections.create(otherId).url());
        connections.setWriteEnabled(otherId, true);

        assertToolError(call(otherToken, "answer_clarification", arguments(clarificationId, ANSWER)), "CLARIFICATION_NOT_FOUND");
        assertToolError(call(token, "answer_clarification", arguments(999999, ANSWER)), "CLARIFICATION_NOT_FOUND");
    }

    @Test
    void 입력이_틀리면_INVALID_ARGUMENT() throws Exception {
        prepare();
        assertToolError(call(token, "answer_clarification", "{\"answer\":\"답\"}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "answer_clarification", "{\"clarificationId\":" + clarificationId + "}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "answer_clarification", arguments(clarificationId, " ")), "INVALID_ARGUMENT");
        assertToolError(call(token, "answer_clarification", arguments(clarificationId, "가".repeat(2001))), "INVALID_ARGUMENT");
    }

    @Test
    void 쓰기_상한은_update_experience_와_합쳐_센다() throws Exception {
        prepare();
        insertChanges(50, "ANSWER_CLARIFICATION");

        assertToolError(call(token, "answer_clarification", arguments(clarificationId, ANSWER)), "WRITE_LIMIT_EXCEEDED");
        String content = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);
        assertToolError(call(token, "update_experience", rewrite(content, "새 본문")), "WRITE_LIMIT_EXCEEDED");

        jdbc.update("delete from mcp_change where type = 'ANSWER_CLARIFICATION'");
        insertChanges(50, "UPDATE_EXPERIENCE");
        assertToolError(call(token, "answer_clarification", arguments(clarificationId, ANSWER)), "WRITE_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from supplement where clarification_id = ?", Long.class, clarificationId))
                .isZero();
    }

    // --- 준비 ---

    // 외부 AI 로 지원을 만들고 추천을 끝내 되묻기 하나를 남긴다
    private void prepare() throws Exception {
        connections.setWriteEnabled(userId, true);
        JsonNode created = toolResult(call(token, "create_application",
                "{\"organization\":\"OO 백엔드 부트캠프\",\"questions\":[{\"text\":\"문제를 해결한 경험을 적어 주세요.\"}]}"));
        applicationChange = created.get("changeId").asLong();
        applicationId = created.get("applicationId").asLong();
        questionId = created.get("questionIds").get(0).asLong();
        settle();

        MatchRequest sent = questions.matchRequest(questionId);
        MatchRequest.ExperiencePart experience = sent.experiences().stream()
                .filter(e -> !e.citations().isEmpty()).findFirst().orElseThrow();
        experienceId = experience.id();
        questions.complete(questionId, sent, new MatchResponse("PROBLEM_SOLVING",
                List.of(new MatchResponse.RequirementResult("문제 해결", "SUPPORTED", List.of(experienceId))),
                List.of(new MatchResponse.RecommendationResult(experienceId, "맞는 경험이다", "해결 과정을 강조한다",
                        List.of(new MatchResponse.EvidenceResult("CITATION", experience.citations().getFirst().id())),
                        List.of())),
                List.of(new MatchResponse.ClarificationResult(experienceId, "어떻게 확인했나요?")), "test-model"));
        settle();
        clarificationId = jdbc.queryForObject("select id from clarification where question_id = ?", Long.class, questionId);
    }

    private long createQuestion() throws Exception {
        JsonNode created = toolResult(call(token, "create_application",
                "{\"organization\":\"둘째 지원\",\"questions\":[{\"text\":\"둘째 문항\"}]}"));
        settle();
        return created.get("questionIds").get(0).asLong();
    }

    private JsonNode answer() throws Exception {
        JsonNode result = toolResult(call(token, "answer_clarification", arguments(clarificationId, ANSWER)));
        settle();
        return result;
    }

    private List<String> sentSupplements() {
        return answers(questions.matchRequest(questionId).experiences());
    }

    private List<String> askedSupplements() {
        return answers(questions.experienceParts(userId));
    }

    private static List<String> answers(List<MatchRequest.ExperiencePart> parts) {
        return parts.stream().flatMap(e -> e.supplements().stream()).map(MatchRequest.SupplementPart::answer).toList();
    }

    private ResultActions activity() throws Exception {
        long activityId = jdbc.queryForObject("select activity_id from experience where id = ?", Long.class, experienceId);
        return web.perform(get("/api/activities/{id}", activityId)).andExpect(status().isOk());
    }

    private static String supplement(long id) {
        return "$.experiences[*].supplements[?(@.id == " + id + ")]";
    }

    private ResultActions confirm(long changeId) throws Exception {
        return web.perform(post("/api/mcp/changes/{id}/confirm", changeId));
    }

    private void settle() {
        em.flush();
        em.clear();
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from mcp_change where type = 'ANSWER_CLARIFICATION'", Long.class);
    }

    private void insertChanges(int count, String type) {
        jdbc.update("insert into mcp_change (user_id, type, client, status, created_at) "
                + "select ?, ?, 'claude', 'APPLIED', now() from generate_series(1, ?)", userId, type, count);
    }

    private String arguments(long clarification, String answer) {
        return json.writeValueAsString(Map.of("clarificationId", clarification, "answer", answer));
    }

    private String rewrite(String expected, String content) {
        return json.writeValueAsString(Map.of("experienceId", experienceId, "expectedContent", expected, "content", content,
                "reason", "사용자가 대화에서 고침"));
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

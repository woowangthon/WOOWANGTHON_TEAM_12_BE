package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.job.ExtractionRunner;
import com.woowangthon.backend.mcp.McpConnectionService;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
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

/**
 * ETC docs/contracts/mcp-api.md 「토큰 범위」 · 「PATCH /api/mcp/connection」 · 「외부 쓰기 도구」 · 「변경 기록」(S-52 · S-53, ADR-010, BE#91).
 * 시드(계약 예시 데이터)를 켜고 테스트 계정으로 돌린다. 분석 자리는 목이고, 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class McpWriteApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    McpConnectionService connections;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    EntityManager em;

    @MockitoBean
    ExtractionRunner extractions;

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

    // --- 쓰기 켜기 · 끄기 ---

    @Test
    void 쓰기는_기본으로_꺼져_있고_쓰기_도구가_보이지도_불리지도_않는다() throws Exception {
        web.perform(get("/api/mcp/connection")).andExpect(jsonPath("$.writeEnabled").value(false));

        rpc(token, "tools/list", "{}").andExpect(jsonPath("$.result.tools[*].name",
                containsInAnyOrder("search_experiences", "list_activities", "get_activity")));
        call(token, "add_source", "{\"text\":\"글\"}").andExpect(jsonPath("$.error.code").value(-32602));
        call(token, "update_experience", "{}").andExpect(jsonPath("$.error.code").value(-32602));
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\"}")
                .andExpect(jsonPath("$.result.instructions", containsString("읽기 전용으로")))
                .andExpect(jsonPath("$.result.instructions", not(containsString("update_experience"))));
    }

    @Test
    void 켜면_같은_주소로_쓰기_도구가_열리고_끄면_다음_요청부터_닫힌다() throws Exception {
        enableWrite();

        web.perform(get("/api/mcp/connection"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.writeEnabled").value(true));
        rpc(token, "tools/list", "{}")
                .andExpect(jsonPath("$.result.tools[*].name", containsInAnyOrder("search_experiences", "list_activities",
                        "get_activity", "get_question", "add_source", "update_experience", "create_application")))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'add_source')].description",
                        hasItem(not(containsString("confirm")))))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'update_experience')].annotations.destructiveHint",
                        hasItem(true)));
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\"}")
                .andExpect(jsonPath("$.result.instructions", not(containsString("읽기 전용으로"))))
                .andExpect(jsonPath("$.result.instructions", containsString("update_experience")));

        web.perform(patch("/api/mcp/connection").contentType(MediaType.APPLICATION_JSON).content("{\"writeEnabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeEnabled").value(false));
        call(token, "add_source", "{\"text\":\"글\"}").andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void 다시_만들면_꺼진_채로_시작하고_끊기는_쓰기를_켠_연결도_지운다() throws Exception {
        enableWrite();

        String body = web.perform(post("/api/mcp/connection")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        rpc(token, "ping", "{}").andExpect(status().isUnauthorized());
        web.perform(get("/api/mcp/connection")).andExpect(jsonPath("$.writeEnabled").value(false));
        assertThat(jdbc.queryForObject("select count(*) from access_token where user_id = ?", Long.class, userId)).isEqualTo(1);

        String newToken = tokenOf(json.readTree(body).get("url").asString());
        rpc(newToken, "tools/list", "{}").andExpect(jsonPath("$.result.tools.length()").value(3));

        token = newToken;
        enableWrite();
        web.perform(delete("/api/mcp/connection"))
                .andExpect(status().isNoContent());
        rpc(newToken, "ping", "{}").andExpect(status().isUnauthorized());
        web.perform(get("/api/mcp/connection"))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.writeEnabled").value(false));
    }

    @Test
    void 쓰기_켜기_입력이_틀리거나_연결이_없으면_오류() throws Exception {
        for (String body : List.of("{}", "{\"writeEnabled\": null}", "{\"writeEnabled\": \"true\"}")) {
            web.perform(patch("/api/mcp/connection").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        connections.delete(userId);
        web.perform(patch("/api/mcp/connection").contentType(MediaType.APPLICATION_JSON).content("{\"writeEnabled\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MCP_CONNECTION_NOT_FOUND"));
    }

    @Test
    void 차곡이_토큰은_지금처럼_확인을_요구하고_외부_전용_도구는_없다() throws Exception {
        String chagok = tokenOf(connections.createChagok(userId).url());

        rpc(chagok, "tools/list", "{}").andExpect(jsonPath("$.result.tools[*].name", containsInAnyOrder("search_experiences",
                "list_activities", "get_activity", "add_source", "create_application")));
        rpc(chagok, "tools/list", "{}")
                .andExpect(jsonPath("$.result.tools[?(@.name == 'create_application')].inputSchema.properties.organizationType.enum[*]",
                        containsInAnyOrder("CLUB", "EXTRACURRICULAR", "EXTERNAL", "BOOTCAMP", "INTERN", "JOB", "OTHER", null)))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'create_application')].inputSchema.properties.jobRole.enum[*]",
                        containsInAnyOrder("BACKEND", "FRONTEND", "MOBILE", "AI_DATA", "INFRA", "DESIGN", "PLANNING", "MARKETING",
                                "BUSINESS", "OTHER", null)));
        assertToolError(call(chagok, "add_source", "{\"text\":\"글\"}"), "CONFIRM_REQUIRED");
        call(chagok, "update_experience", "{}").andExpect(jsonPath("$.error.code").value(-32602));
        rpc(chagok, "initialize", "{\"protocolVersion\":\"2025-06-18\"}").andExpect(jsonPath("$.result.instructions", containsString("읽기 전용으로")));
        verify(extractions, never()).run(anyLong());
    }

    // --- client ---

    @Test
    void clientInfo_이름은_제어_문자를_빼고_앞_40자만_원본_이름에_남는다() throws Exception {
        enableWrite();
        String longName = "가".repeat(45);
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"cl\\u0000au\\u0007de\\n" + longName + "\"}}").andExpect(status().isOk());

        long sourceId = toolResult(call(token, "add_source", "{\"text\":\"글\"}")).get("sourceId").asLong();

        assertThat(sourceName(sourceId)).startsWith("claude" + "가".repeat(34) + " 에서 보냄 — ");
    }

    @Test
    void clientInfo_가_없으면_외부_AI_로_보인다() throws Exception {
        enableWrite();
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"\\u0007 \"}}").andExpect(status().isOk());

        long sourceId = toolResult(call(token, "add_source", "{\"text\":\"글\"}")).get("sourceId").asLong();

        assertThat(sourceName(sourceId)).startsWith("외부 AI 에서 보냄 — ");
    }

    // --- add_source ---

    @Test
    void add_source_는_바로_저장하고_분석을_시작하고_변경_기록을_남긴다() throws Exception {
        enableWrite();
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"claude-ai\"}}").andExpect(status().isOk());
        int usedBefore = used();

        JsonNode result = toolResult(call(token, "add_source", "{\"text\":\"대화에서 나온 글\"}"));

        long sourceId = result.get("sourceId").asLong();
        long jobId = result.get("jobId").asLong();
        assertThat(result.get("status").asString()).isEqualTo("ANALYZING");
        verify(extractions).run(jobId);
        em.flush();
        assertThat(jdbc.queryForMap("select kind, text from source where id = ?", sourceId))
                .containsEntry("kind", "MCP").containsEntry("text", "대화에서 나온 글");
        assertThat(sourceName(sourceId)).matches("claude-ai 에서 보냄 — \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(jdbc.queryForMap("select type, client, status, source_id, job_id from mcp_change where id = ?",
                result.get("changeId").asLong()))
                .containsEntry("type", "ADD_SOURCE").containsEntry("client", "claude-ai").containsEntry("status", "APPLIED")
                .containsEntry("source_id", sourceId).containsEntry("job_id", jobId);
        assertThat(used()).isEqualTo(usedBefore + 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 분석은_쓰기와_변경_기록이_커밋된_뒤_시작한다() throws Exception {
        AtomicLong recorded = new AtomicLong(-1);
        doAnswer(invocation -> {
            // 다른 연결에서 읽는다 — 커밋되지 않았으면 0
            recorded.set(jdbc.queryForObject("select count(*) from mcp_change where job_id = ?", Long.class,
                    invocation.<Long>getArgument(0)));
            return null;
        }).when(extractions).run(anyLong());
        try {
            enableWrite();

            toolResult(call(token, "add_source", "{\"text\":\"글\"}"));

            assertThat(recorded.get()).isEqualTo(1);
        } finally {
            jdbc.update("delete from mcp_change where user_id = ?", userId);
            jdbc.update("delete from analysis_job where user_id = ?", userId);
            jdbc.update("delete from source where kind = 'MCP'");
            jdbc.update("delete from analysis_usage where user_id = ?", userId);
            connections.delete(userId);
        }
    }

    @Test
    void add_source_의_글이_비었거나_한도를_넘으면_아무것도_남기지_않는다() throws Exception {
        enableWrite();
        long sources = count("source");

        assertToolError(call(token, "add_source", "{\"text\":\"  \"}"), "EMPTY_TEXT");
        assertToolError(call(token, "add_source", "{\"text\":\"" + "가".repeat(50001) + "\"}"), "TEXT_TOO_LONG");
        assertToolError(call(token, "add_source", "{}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "add_source", "{\"text\":1}"), "INVALID_ARGUMENT");

        assertThat(count("source")).isEqualTo(sources);
        assertThat(count("mcp_change")).isZero();
        verify(extractions, never()).run(anyLong());
    }

    @Test
    void add_source_는_웹과_같은_하루_분석_한도를_센다() throws Exception {
        enableWrite();
        jdbc.update("update app_user set daily_analysis_limit = ? where id = ?", used() + 1, userId);
        web.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"TEXT\", \"text\": \"웹에서 넣은 글\"}"))
                .andExpect(status().isAccepted());
        long sources = count("source");

        assertToolError(call(token, "add_source", "{\"text\":\"글\"}"), "DAILY_LIMIT_EXCEEDED");

        assertThat(count("source")).isEqualTo(sources);
        assertThat(count("mcp_change")).isZero();
    }

    @Test
    void POST_api_sources_는_MCP_종류를_받지_않는다() throws Exception {
        web.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"MCP\", \"text\": \"글\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // --- update_experience ---

    @Test
    void update_experience_는_본문만_바꾸고_확인_필요로_낮추고_변경_기록을_남긴다() throws Exception {
        enableWrite();
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"claude-ai\"}}").andExpect(status().isOk());
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        long activityId = (Long) e.get("activity_id");
        String before = (String) e.get("content");
        List<Map<String, Object>> otherBefore = untouchedColumns(id);

        JsonNode result = toolResult(call(token, "update_experience", arguments(id, before, "새 본문", "사용자가 대화에서 고침")));

        long changeId = result.get("changeId").asLong();
        assertThat(result.get("experienceId").asLong()).isEqualTo(id);
        assertThat(result.get("sourceStatus").asString()).isEqualTo("NEEDS_REVIEW");
        em.flush();
        assertThat(untouchedColumns(id)).isEqualTo(otherBefore);
        assertThat(jdbc.queryForMap("select type, client, status, reason, experience_id, activity_id, before_content, "
                + "after_content, before_source_status from mcp_change where id = ?", changeId))
                .containsEntry("type", "UPDATE_EXPERIENCE").containsEntry("client", "claude-ai")
                .containsEntry("status", "APPLIED").containsEntry("reason", "사용자가 대화에서 고침")
                .containsEntry("experience_id", id).containsEntry("activity_id", activityId)
                .containsEntry("before_content", before).containsEntry("after_content", "새 본문")
                .containsEntry("before_source_status", "SOURCE_CONFIRMED");
        assertThat(count("analysis_usage")).isZero(); // LLM 을 안 불러 분석 한도와 따로 센다

        String path = "$.experiences[?(@.id == " + id + ")]";
        web.perform(get("/api/activities/{id}", activityId))
                .andExpect(jsonPath(path + ".content", contains("새 본문")))
                .andExpect(jsonPath(path + ".sourceStatus", contains("NEEDS_REVIEW")))
                .andExpect(jsonPath(path + ".mcpChange.changeId", contains((int) changeId)))
                .andExpect(jsonPath(path + ".mcpChange.client", contains("claude-ai")));
        JsonNode hit = toolResult(call(token, "search_experiences", "{\"query\":\"새 본문\"}")).get("experiences").get(0);
        assertThat(hit.get("mcpChange").get("changeId").asLong()).isEqualTo(changeId);
    }

    @Test
    void 사용자가_웹에서_고치면_mcpChange_가_비고_내가_입력이_된다() throws Exception {
        enableWrite();
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        toolResult(call(token, "update_experience", arguments(id, (String) e.get("content"), "새 본문", "이유")));

        web.perform(patch("/api/experiences/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"내가 고친 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.mcpChange").value(nullValue()));
    }

    @Test
    void 평소_경험의_mcpChange_는_null_이다() throws Exception {
        Map<String, Object> e = confirmedExperience();

        web.perform(get("/api/activities/{id}", e.get("activity_id")))
                .andExpect(jsonPath("$.experiences[0].mcpChange").value(nullValue()));
    }

    @Test
    void update_experience_입력이_틀리면_INVALID_ARGUMENT() throws Exception {
        enableWrite();
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        String before = (String) e.get("content");

        for (String field : List.of("actor", "competencies", "userId")) {
            Map<String, Object> extra = new LinkedHashMap<>(Map.of("experienceId", id, "expectedContent", before,
                    "content", "새 본문", "reason", "이유"));
            extra.put(field, "TEAM");
            assertToolError(call(token, "update_experience", json.writeValueAsString(extra)), "INVALID_ARGUMENT");
        }
        assertToolError(call(token, "update_experience", "{\"expectedContent\":\"a\",\"content\":\"b\",\"reason\":\"c\"}"),
                "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience", "{\"experienceId\":" + id + ",\"content\":\"b\",\"reason\":\"c\"}"),
                "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience",
                "{\"experienceId\":" + id + ",\"expectedContent\":\"a\",\"content\":\"b\"}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience", arguments(id, before, " ", "이유")), "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience", arguments(id, before, "새 본문", " ")), "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience", arguments(id, before, before, "이유")), "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience", arguments(id, before, "가".repeat(5001), "이유")), "INVALID_ARGUMENT");
        assertToolError(call(token, "update_experience", arguments(id, before, "새 본문", "가".repeat(501))), "INVALID_ARGUMENT");

        assertNothingChanged(id, before);
    }

    @Test
    void 읽은_본문과_다르면_STALE_CONTENT() throws Exception {
        enableWrite();
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");

        assertToolError(call(token, "update_experience", arguments(id, "예전 본문", "새 본문", "이유")), "STALE_CONTENT");

        assertNothingChanged(id, (String) e.get("content"));
    }

    @Test
    void 남의_경험은_EXPERIENCE_NOT_FOUND_이고_그대로다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        String otherToken = tokenOf(connections.create(otherId).url());
        connections.setWriteEnabled(otherId, true);

        assertToolError(call(otherToken, "update_experience", arguments(id, (String) e.get("content"), "새 본문", "이유")),
                "EXPERIENCE_NOT_FOUND");
        assertToolError(call(otherToken, "update_experience", arguments(999999, "a", "b", "c")), "EXPERIENCE_NOT_FOUND");

        assertNothingChanged(id, (String) e.get("content"));
    }

    @Test
    void 하루_쓰기_상한을_넘으면_WRITE_LIMIT_EXCEEDED_이고_어제_것은_세지_않는다() throws Exception {
        enableWrite();
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        insertChanges(49, "now()");
        insertChanges(5, "now() - interval '2 days'");
        String before = (String) e.get("content");

        toolResult(call(token, "update_experience", arguments(id, before, "쉰 번째", "이유")));
        assertToolError(call(token, "update_experience", arguments(id, "쉰 번째", "쉰한 번째", "이유")), "WRITE_LIMIT_EXCEEDED");

        em.flush();
        assertThat(jdbc.queryForObject("select content from experience where id = ?", String.class, id)).isEqualTo("쉰 번째");
    }

    // --- 도우미 ---

    private void enableWrite() throws Exception {
        web.perform(patch("/api/mcp/connection").contentType(MediaType.APPLICATION_JSON).content("{\"writeEnabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeEnabled").value(true));
    }

    // 근거 · 보완 · AI 역량이 붙은 시드 경험 — 「그대로」를 볼 수 있게
    private Map<String, Object> confirmedExperience() {
        return jdbc.queryForMap("""
                select e.id, e.activity_id, e.content from experience e
                join activity a on a.id = e.activity_id
                where a.user_id = ? and e.source_status = 'SOURCE_CONFIRMED' and e.competencies_by = 'AI'
                  and exists (select 1 from citation c where c.experience_id = e.id)
                order by (select count(*) from supplement s where s.experience_id = e.id) desc, e.id
                limit 1
                """, userId);
    }

    // 고치기가 건드리면 안 되는 칸과 근거 · 보완
    private List<Map<String, Object>> untouchedColumns(long id) {
        return jdbc.queryForList("""
                select e.competencies::text, e.competencies_by, e.actor, e.position,
                       (select string_agg(c.id || ':' || c.quote, '|' order by c.id) from citation c where c.experience_id = e.id) citations,
                       (select string_agg(s.id || ':' || s.answer, '|' order by s.id) from supplement s where s.experience_id = e.id) supplements
                from experience e where e.id = ?
                """, id);
    }

    private void assertNothingChanged(long id, String content) {
        em.flush();
        assertThat(jdbc.queryForMap("select content, source_status, mcp_change_id from experience where id = ?", id))
                .containsEntry("content", content).containsEntry("source_status", "SOURCE_CONFIRMED")
                .containsEntry("mcp_change_id", null);
        assertThat(count("mcp_change")).isZero();
    }

    private void insertChanges(int count, String createdAt) {
        jdbc.update("insert into mcp_change (user_id, type, client, status, created_at) "
                + "select ?, 'UPDATE_EXPERIENCE', 'claude', 'APPLIED', " + createdAt + " from generate_series(1, ?)", userId, count);
    }

    private String arguments(long id, String expected, String content, String reason) {
        return json.writeValueAsString(Map.of("experienceId", id, "expectedContent", expected, "content", content, "reason", reason));
    }

    private String sourceName(long sourceId) {
        em.flush();
        return jdbc.queryForObject("select name from source where id = ?", String.class, sourceId);
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

package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityCategory;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import com.woowangthon.backend.mcp.McpConnectionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ETC docs/contracts/mcp-api.md — 연결 주소 API 3개 · POST /mcp/{token} · 읽기 도구 3개 (SPEC F-11 · S-32, BE#41).
 * 시드(계약 예시 데이터)를 켜고 테스트 계정으로 돌린다. 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password",
        "app.mcp.base-url=https://api.example.test/mcp"})
@Transactional
class McpApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    ActivityRepository activities;

    @Autowired
    SupplementRepository supplements;

    @Autowired
    McpConnectionService connections;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    MockMvc web;
    MockMvc client;
    long userId;
    long appId;
    long studyId;
    String token;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        web = TestLogin.mvc(context, userId);
        client = MockMvcBuilders.webAppContextSetup(context).build();
        List<Activity> timeline = activities.findTimeline(userId);
        studyId = timeline.get(0).getId();
        appId = timeline.get(1).getId();
        token = tokenOf(connections.create(userId).url());
    }

    // --- 연결 주소 API ---

    @Test
    void 연결이_없으면_connected_false() throws Exception {
        web.perform(delete("/api/mcp/connection")).andExpect(status().isNoContent());

        web.perform(get("/api/mcp/connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.createdAt").value(nullValue()))
                .andExpect(jsonPath("$.lastUsedAt").value(nullValue()));
    }

    @Test
    void 주소를_만들면_url_은_그때만_오고_해시만_저장된다() throws Exception {
        String body = web.perform(post("/api/mcp/connection"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(startsWith("https://api.example.test/mcp/")))
                .andExpect(jsonPath("$.createdAt").value(containsString("+09:00")))
                .andReturn().getResponse().getContentAsString();
        String newToken = tokenOf(json.readTree(body).get("url").asString());

        assertThat(newToken).matches("[A-Za-z0-9_-]{43}");
        List<String> hashes = jdbc.queryForList("select token_hash from access_token where user_id = ?", String.class, userId);
        assertThat(hashes).containsExactly(sha256(newToken));

        web.perform(get("/api/mcp/connection"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.lastUsedAt").value(nullValue()))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void MCP_요청이_오면_lastUsedAt_이_찍힌다() throws Exception {
        rpc(token, "ping", "{}").andExpect(status().isOk());

        web.perform(get("/api/mcp/connection"))
                .andExpect(jsonPath("$.lastUsedAt").value(containsString("+09:00")));
    }

    @Test
    void 다시_만들면_예전_주소는_바로_401() throws Exception {
        String body = web.perform(post("/api/mcp/connection")).andReturn().getResponse().getContentAsString();
        String newToken = tokenOf(json.readTree(body).get("url").asString());

        rpc(token, "ping", "{}").andExpect(status().isUnauthorized()).andExpect(content().string(""));
        rpc(newToken, "ping", "{}").andExpect(status().isOk());
    }

    @Test
    void 끊으면_401_이고_다시_끊어도_204() throws Exception {
        web.perform(delete("/api/mcp/connection")).andExpect(status().isNoContent());
        web.perform(delete("/api/mcp/connection")).andExpect(status().isNoContent());

        rpc(token, "ping", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void 연결_주소_API_는_로그인해야_쓴다() throws Exception {
        client.perform(get("/api/mcp/connection")).andExpect(status().isUnauthorized());
        client.perform(post("/api/mcp/connection")).andExpect(status().isUnauthorized());
    }

    // --- MCP 엔드포인트 ---

    @Test
    void 없는_토큰은_본문_없이_401() throws Exception {
        rpc("not-a-token", "initialize", "{}").andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void initialize_는_보낸_버전과_읽는_규칙을_돌려준다() throws Exception {
        rpc(token, "initialize", """
                {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("woowang-experiences"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.instructions").value(containsString("NEEDS_REVIEW 를 확인된 사실처럼 말하지 않는다")));
    }

    @Test
    void 모르는_버전이면_지원하는_최신으로_답한다() throws Exception {
        rpc(token, "initialize", "{\"protocolVersion\":\"1999-01-01\"}")
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
    }

    @Test
    void 버전이_없거나_문자열이_아니어도_지원하는_최신으로_답한다() throws Exception {
        rpc(token, "initialize", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
        rpc(token, "initialize", "{\"protocolVersion\":20250618}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
    }

    @Test
    void 알림은_본문_없이_202() throws Exception {
        client.perform(post("/mcp/{token}", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void 모르는_메서드는_32601_이고_GET_은_405() throws Exception {
        rpc(token, "resources/list", "{}").andExpect(jsonPath("$.error.code").value(-32601));
        client.perform(get("/mcp/{token}", token)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void 읽을_수_없는_JSON_은_32700() throws Exception {
        client.perform(post("/mcp/{token}", token).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Test
    void 도구_목록은_셋이고_모두_읽기_전용() throws Exception {
        rpc(token, "tools/list", "{}")
                .andExpect(jsonPath("$.result.tools", hasSize(3)))
                .andExpect(jsonPath("$.result.tools[*].name").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "search_experiences", "list_activities", "get_activity")))
                .andExpect(jsonPath("$.result.tools[*].annotations.readOnlyHint").value(everyItem(org.hamcrest.Matchers.is(true))))
                .andExpect(jsonPath("$.result.tools[*].inputSchema.type").value(everyItem(org.hamcrest.Matchers.is("object"))));
    }

    @Test
    void 없는_도구는_32602() throws Exception {
        call(token, "delete_everything", "{}").andExpect(jsonPath("$.error.code").value(-32602));
    }

    // --- search_experiences ---

    @Test
    void WebSocket_을_찾으면_근거와_보완이_빠짐없이_나온다() throws Exception {
        Experience websocket = activities.findById(appId).orElseThrow().getExperiences().getFirst();
        supplements.save(new Supplement(websocket, null, null, "직접 고친 부분은 어디였나요?", "STOMP 엔드포인트를 만들었다"));

        JsonNode result = toolResult(call(token, "search_experiences", "{\"query\":\"websocket\"}"));

        assertThat(result.get("total").asInt()).isEqualTo(1);
        JsonNode hit = result.get("experiences").get(0);
        assertThat(hit.get("id").asLong()).isEqualTo(websocket.getId());
        assertThat(hit.get("activity").get("name").asString()).isEqualTo("바꿔요 — 캠퍼스 중고거래 앱");
        assertThat(hit.get("activity").get("period").get("start").asString()).isEqualTo("2025-03");
        assertThat(hit.has("activityId")).isFalse();
        assertThat(hit.get("competenciesBy").asString()).isEqualTo("AI");
        assertThat(hit.get("sourceStatus").asString()).isEqualTo("SOURCE_CONFIRMED");
        assertThat(hit.get("citations").get(0).get("sourceName").asString()).isEqualTo("바꿔요-회고.md");
        assertThat(hit.get("citations").get(0).get("quote").asString()).contains("WebSocket");
        assertThat(hit.get("supplements").get(0).get("answer").asString()).isEqualTo("STOMP 엔드포인트를 만들었다");
    }

    @Test
    void 결과는_text_와_structuredContent_에_같은_값으로_실린다() throws Exception {
        String body = call(token, "search_experiences", "{\"query\":\"websocket\"}")
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = json.readTree(body).get("result");

        assertThat(json.readTree(result.get("content").get(0).get("text").asString())).isEqualTo(result.get("structuredContent"));
    }

    @Test
    void 보완_답에_있는_낱말로도_찾는다() throws Exception {
        Experience retro = activities.findById(appId).orElseThrow().getExperiences().get(1);
        supplements.save(new Supplement(retro, null, null, "회고에서 무엇을 바꿨나요?", "Notion 템플릿을 만들었다"));

        JsonNode result = toolResult(call(token, "search_experiences", "{\"query\":\"notion\"}"));

        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(result.get("experiences").get(0).get("id").asLong()).isEqualTo(retro.getId());
    }

    @Test
    void 낱말은_하나라도_맞으면_되고_많이_맞은_것이_앞이다() throws Exception {
        JsonNode result = toolResult(call(token, "search_experiences", "{\"query\":\"회고 websocket 폴링\"}"));

        assertThat(result.get("total").asInt()).isEqualTo(2);
        assertThat(result.get("experiences").get(0).get("content").asString()).contains("WebSocket");
        assertThat(result.get("experiences").get(1).get("content").asString()).contains("회고");
    }

    @Test
    void 조건이_없으면_전부이고_최근_활동이_앞이다() throws Exception {
        JsonNode result = toolResult(call(token, "search_experiences", "{}"));

        assertThat(result.get("total").asInt()).isEqualTo(5);
        assertThat(result.get("experiences").get(0).get("activity").get("id").asLong()).isEqualTo(appId);
        assertThat(result.get("experiences").get(4).get("activity").get("id").asLong()).isEqualTo(studyId);
    }

    @Test
    void limit_은_자르기만_하고_total_은_전체_수() throws Exception {
        JsonNode result = toolResult(call(token, "search_experiences", "{\"limit\":2}"));

        assertThat(result.get("total").asInt()).isEqualTo(5);
        assertThat(result.get("experiences").size()).isEqualTo(2);
    }

    @Test
    void 역량과_연도로_거른다() throws Exception {
        JsonNode operation = toolResult(call(token, "search_experiences", "{\"competencies\":[\"OPERATION\"]}"));
        assertThat(operation.get("total").asInt()).isEqualTo(1);
        assertThat(operation.get("experiences").get(0).get("activity").get("id").asLong()).isEqualTo(studyId);

        // 진행 중인 스터디(2024-09 ~)는 올해까지, 바꿔요(2025-03 ~ 2025-06)는 2025 만
        assertThat(toolResult(call(token, "search_experiences", "{\"year\":\"2025\"}")).get("total").asInt()).isEqualTo(5);
        assertThat(toolResult(call(token, "search_experiences", "{\"year\":\"2024\"}")).get("total").asInt()).isEqualTo(1);
        assertThat(toolResult(call(token, "search_experiences", "{\"year\":\"2023\"}")).get("total").asInt()).isEqualTo(0);
        assertThat(toolResult(call(token, "search_experiences",
                "{\"year\":\"2025\",\"competencies\":[\"COLLABORATION\"]}")).get("total").asInt()).isEqualTo(1);
    }

    // 앱 경험 찾기(GET /api/experiences, S-58)와 한 검색이다(BE#96 — ExperienceSearch). 검색 규칙의 시험은 이 파일이 맡고,
    // 앱 쪽 시험(ExperienceSearchApiTest)은 앱에만 있는 조건 · 응답 모양만 본다 — 그래서 둘이 같은지는 여기서 본다
    @Test
    void 같은_입력이면_앱_검색과_같은_경험_같은_순서() throws Exception {
        Experience websocket = activities.findById(appId).orElseThrow().getExperiences().getFirst();
        supplements.save(new Supplement(websocket, null, null, "직접 고친 부분은 어디였나요?", "STOMP 엔드포인트를 만들었다"));

        record Case(String mcpArguments, String query, String competencies, String year) {
        }
        List<Case> cases = List.of(
                new Case("{}", null, null, null),
                new Case("{\"query\":\"websocket 회고 배포\"}", "websocket 회고 배포", null, null),
                new Case("{\"query\":\"STOMP 맡\"}", "STOMP 맡", null, null),
                new Case("{\"competencies\":[\"IMPLEMENTATION\",\"COLLABORATION\"]}", null, "IMPLEMENTATION,COLLABORATION", null),
                new Case("{\"year\":\"2024\"}", null, null, "2024"),
                new Case("{\"query\":\"맡 문제\",\"competencies\":[\"IMPLEMENTATION\",\"OPERATION\"],\"year\":\"2025\"}",
                        "맡 문제", "IMPLEMENTATION,OPERATION", "2025"));
        for (Case c : cases) {
            JsonNode mcp = toolResult(call(token, "search_experiences", c.mcpArguments()));
            List<Long> mcpIds = new java.util.ArrayList<>();
            mcp.get("experiences").forEach(e -> mcpIds.add(e.get("id").asLong()));

            var request = get("/api/experiences");
            if (c.query() != null) {
                request.param("query", c.query());
            }
            if (c.competencies() != null) {
                request.param("competencies", c.competencies());
            }
            if (c.year() != null) {
                request.param("year", c.year());
            }
            JsonNode app = json.readTree(web.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString());
            List<Long> appIds = new java.util.ArrayList<>();
            app.get("experiences").forEach(e -> appIds.add(e.get("id").asLong()));

            assertThat(appIds).as(c.mcpArguments()).isEqualTo(mcpIds);
            assertThat(app.get("total").asInt()).as(c.mcpArguments()).isEqualTo(mcp.get("total").asInt());
        }
    }

    @Test
    void 맞는_게_없으면_오류가_아니라_빈_목록() throws Exception {
        call(token, "search_experiences", "{\"query\":\"쿠버네티스\"}")
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.total").value(0))
                .andExpect(jsonPath("$.result.structuredContent.experiences", hasSize(0)));
    }

    @Test
    void 입력_모양이_틀리면_INVALID_ARGUMENT() throws Exception {
        for (String args : List.of("{\"competencies\":[\"FOO\"]}", "{\"year\":\"25\"}", "{\"limit\":0}",
                "{\"limit\":51}", "{\"query\":3}", "{\"competencies\":\"OPERATION\"}")) {
            assertToolError(call(token, "search_experiences", args), "INVALID_ARGUMENT");
        }
    }

    // --- list_activities · get_activity ---

    @Test
    void list_activities_는_활동_목록_API_와_같다() throws Exception {
        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);
        String rest = web.perform(get("/api/activities")).andReturn().getResponse().getContentAsString();

        // mergeSuggestion(S-44 — 화면에서 사용자가 정할 AI 제안) · note(S-34) · 분류(S-57 — 화면 정리용)는 MCP 에 싣지 않는다
        JsonNode expected = json.readTree(rest);
        expected.get("activities").forEach(a -> ((ObjectNode) a).remove(List.of("mergeSuggestion", "note", "category", "categoryBy")));
        JsonNode result = toolResult(call(token, "list_activities", "{}"));

        assertThat(result).isEqualTo(expected);
        assertThat(result.get("activities").get(0).has("mergeSuggestion")).isFalse();
        assertThat(result.get("activities").get(0).has("note")).isFalse();
        assertThat(result.get("activities").get(1).has("category")).isFalse();
        assertThat(result.get("activities").get(1).has("categoryBy")).isFalse();
    }

    @Test
    void get_activity_는_활동_세부_API_와_같다() throws Exception {
        Experience websocket = activities.findById(appId).orElseThrow().getExperiences().getFirst();
        supplements.save(new Supplement(websocket, null, null, "질문", "답"));
        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);
        String rest = web.perform(get("/api/activities/{id}", appId)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(rest).get("category").asString()).isEqualTo("PROJECT");

        JsonNode result = toolResult(call(token, "get_activity", "{\"activityId\":" + appId + "}"));
        ObjectNode expected = (ObjectNode) json.readTree(rest);
        expected.remove(List.of("mergeSuggestion", "note", "links", "category", "categoryBy"));
        // 쓰인 지원(S-38)은 활동 세부 화면의 것, 공개 프로필 선택(S-65) · 저장(S-71)은 웹에서만 — MCP 경험엔 없다
        expected.get("experiences").forEach(e -> ((ObjectNode) e).remove(List.of("usedIn", "shared", "saved")));

        assertThat(result).isEqualTo(expected);
        assertThat(result.get("experiences").get(0).has("usedIn")).isFalse();
        assertThat(result.get("experiences").get(0).has("shared")).isFalse();
        assertThat(result.has("mergeSuggestion")).isFalse();
        assertThat(result.has("note")).isFalse();
        assertThat(result.has("links")).isFalse();
        assertThat(result.has("category")).isFalse();
        assertThat(result.has("categoryBy")).isFalse();
        assertThat(result.get("experiences").get(0).get("supplements").get(0).get("answer").asString()).isEqualTo("답");
    }

    @Test
    void get_activity_입력이_틀리거나_없는_활동이면_도구_오류() throws Exception {
        assertToolError(call(token, "get_activity", "{}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "get_activity", "{\"activityId\":\"일곱\"}"), "INVALID_ARGUMENT");
        assertToolError(call(token, "get_activity", "{\"activityId\":999999}"), "ACTIVITY_NOT_FOUND");
    }

    // --- 다른 사용자의 데이터 ---

    @Test
    void 다른_사용자의_토큰으로는_이_사용자_데이터가_안_나온다() throws Exception {
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        String otherToken = tokenOf(connections.create(otherId).url());

        assertThat(toolResult(call(otherToken, "search_experiences", "{}")).get("total").asInt()).isEqualTo(0);
        assertThat(toolResult(call(otherToken, "list_activities", "{}")).get("activities").size()).isEqualTo(0);
        // 남의 활동은 있는지도 알려 주지 않는다 — 없는 활동과 같은 답
        assertToolError(call(otherToken, "get_activity", "{\"activityId\":" + appId + "}"), "ACTIVITY_NOT_FOUND");
        // 사용자 ID 를 입력에 섞어도 무시한다
        assertThat(toolResult(call(otherToken, "search_experiences", "{\"userId\":" + userId + "}")).get("total").asInt())
                .isEqualTo(0);
        // 다른 사용자가 연결을 만들어도 이 사용자의 연결은 그대로
        rpc(token, "ping", "{}").andExpect(status().isOk());
    }

    // --- 도우미 ---

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

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

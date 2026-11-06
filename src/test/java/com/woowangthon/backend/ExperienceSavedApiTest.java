package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.mcp.GetActivityTool;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.mcp.McpTool;
import com.woowangthon.backend.mcp.SearchExperiencesTool;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ETC docs/contracts/experience-api.md 「PUT /api/experiences/{experienceId}/saved」 · 「GET /api/experiences」 saved (S-71, BE#119).
 * 시드(테스트 계정): 「바꿔요」 경험 4개 · 「알고리즘 스터디」 1개. 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class ExperienceSavedApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ActivityRepository activities;

    @Autowired
    AppUserRepository users;

    @Autowired
    GetActivityTool getActivity;

    @Autowired
    SearchExperiencesTool searchExperiences;

    @Autowired
    List<McpTool> tools;

    @Autowired
    EntityManager em;

    @Autowired
    ObjectMapper json;

    MockMvc mvc;
    long userId;
    long appId;
    long studyId;
    long experienceId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
        studyId = timeline.get(0).getId();
        appId = timeline.get(1).getId();
        experienceId = timeline.get(1).getExperiences().get(0).getId();
    }

    @Test
    void 새_경험은_저장되지_않았고_세부와_찾기_응답에_실린다() throws Exception {
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].saved").value(false));
        mvc.perform(get("/api/experiences"))
                .andExpect(jsonPath("$.experiences[0].saved").value(false));
    }

    @Test
    void 켜고_꺼도_출처_상태와_역량_출처는_그대로고_새로_읽어도_남는다() throws Exception {
        String before = mvc.perform(get("/api/activities/{id}", appId)).andReturn().getResponse().getContentAsString();
        String sourceStatus = JsonPath.read(before, "$.experiences[0].sourceStatus");
        String competenciesBy = JsonPath.read(before, "$.experiences[0].competenciesBy");

        save(experienceId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(experienceId))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.sourceStatus").value(sourceStatus))
                .andExpect(jsonPath("$.competenciesBy").value(competenciesBy));
        em.flush();
        em.clear();
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].saved").value(true));

        save(experienceId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.sourceStatus").value(sourceStatus));
    }

    @Test
    void 이미_그_값이면_200() throws Exception {
        save(experienceId, false).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
        save(experienceId, true).andExpect(status().isOk());
        save(experienceId, true).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
    }

    @Test
    void saved_값이_없거나_불리언이_아니면_400() throws Exception {
        for (String body : List.of("{}", "{\"saved\":null}", "{\"saved\":\"true\"}", "{\"saved\":1}")) {
            expectError(mvc.perform(put("/api/experiences/{id}/saved", experienceId)
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "INVALID_REQUEST");
        }
        expectError(mvc.perform(put("/api/experiences/{id}/saved", experienceId)
                .contentType(MediaType.APPLICATION_JSON)), 400, "INVALID_REQUEST");
    }

    @Test
    void 없는_경험과_남의_경험은_404_이고_바뀌지_않는다() throws Exception {
        expectError(save(999999L, true), 404, "EXPERIENCE_NOT_FOUND");

        long otherUserId = users.saveAndFlush(new AppUser("saved-other", "hash", null)).getId();
        expectError(TestLogin.mvc(context, otherUserId).perform(put("/api/experiences/{id}/saved", experienceId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"saved\":true}")), 404, "EXPERIENCE_NOT_FOUND");

        mvc.perform(get("/api/experiences").param("saved", "true")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        expectError(MockMvcBuilders.webAppContextSetup(context).build()
                .perform(put("/api/experiences/{id}/saved", experienceId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"saved\":true}")), 401, "UNAUTHENTICATED");
    }

    // --- 경험 찾기 ---

    @Test
    void 저장한_경험만_거르고_다른_조건과_그리고로_묶는다() throws Exception {
        long studyExperienceId = activities.findById(studyId).orElseThrow().getExperiences().get(0).getId();
        save(experienceId, true).andExpect(status().isOk());
        save(studyExperienceId, true).andExpect(status().isOk());

        mvc.perform(get("/api/experiences").param("saved", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.experiences[0].saved").value(true))
                .andExpect(jsonPath("$.experiences[1].saved").value(true));
        mvc.perform(get("/api/experiences").param("saved", "true").param("query", "존재하지않는낱말"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/experiences").param("saved", "true").param("sourceStatus", "NEEDS_REVIEW"))
                .andExpect(jsonPath("$.total").value(0));
        // 빼면 · 비우면 거르지 않는다
        mvc.perform(get("/api/experiences")).andExpect(jsonPath("$.total").value(5));
        mvc.perform(get("/api/experiences").param("saved", "")).andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void true_가_아닌_saved_는_400() throws Exception {
        for (String value : List.of("false", "TRUE", "1", "yes")) {
            expectError(mvc.perform(get("/api/experiences").param("saved", value)), 400, "INVALID_REQUEST");
        }
    }

    // --- 옮기기 · 합치기 ---

    @Test
    void 경험을_옮겨도_저장은_그대로다() throws Exception {
        save(experienceId, true).andExpect(status().isOk());

        postJson("/api/experiences/" + experienceId + "/move", "{\"toActivityId\": " + studyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(studyId))
                .andExpect(jsonPath("$.saved").value(true));
    }

    @Test
    void 활동을_합쳐도_저장은_그대로다() throws Exception {
        save(experienceId, true).andExpect(status().isOk());

        postJson("/api/activities/" + studyId + "/merge", "{\"fromActivityId\": " + appId + "}")
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/experiences").param("saved", "true"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.experiences[0].id").value(experienceId))
                .andExpect(jsonPath("$.experiences[0].activity.id").value(studyId));
    }

    // --- 외부 AI · 공개 프로필 ---

    @Test
    void 외부_AI_읽기에는_saved_가_보이지_않는다() throws Exception {
        save(experienceId, true).andExpect(status().isOk());
        em.flush();
        em.clear();
        McpConnectionService.Authenticated caller = new McpConnectionService.Authenticated(1L, userId, "READ", "claude-ai");

        JsonNode detail = json.valueToTree(getActivity.call(caller, json.createObjectNode().put("activityId", appId)));
        JsonNode found = json.valueToTree(searchExperiences.call(caller, json.createObjectNode()));

        assertThat(detail.get("experiences").get(0).has("saved")).isFalse();
        assertThat(found.get("experiences").get(0).has("saved")).isFalse();
    }

    @Test
    void 외부_AI_와_차곡이의_도구에는_saved_를_받는_칸이_없다() {
        // 차곡이도 이 도구들의 클라이언트다(ADR-008)
        assertThat(tools).isNotEmpty();
        for (McpTool tool : tools) {
            for (String scope : List.of("READ", "WRITE")) {
                String definition = json.writeValueAsString(tool.definitionFor(scope));
                assertThat(definition).as(tool.name()).doesNotContain("\"saved\"");
            }
        }
    }

    @Test
    void 공개_프로필에는_saved_가_보이지_않는다() throws Exception {
        save(experienceId, true).andExpect(status().isOk());
        mvc.perform(put("/api/experiences/{id}/shared", experienceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"shared\":true}")).andExpect(status().isOk());
        String link = mvc.perform(post("/api/public-profile/link")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(link, "$.token");

        String body = MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/api/public/profiles/{token}", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("saved");
    }

    // --- 도우미 ---

    private ResultActions save(long id, boolean saved) throws Exception {
        return mvc.perform(put("/api/experiences/{id}/saved", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"saved\":" + saved + "}"));
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}

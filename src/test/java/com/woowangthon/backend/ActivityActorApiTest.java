package com.woowangthon.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.experience.Experience;
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

/**
 * ETC docs/contracts/experience-api.md 「POST /api/activities/{activityId}/actor」 (S-76, BE#125).
 * 시드(테스트 계정): 「바꿔요」 경험 4개(ME · TEAM · TEAM · ME 확인 필요) · 「알고리즘 스터디」 1개(ME).
 * 테스트마다 「바꿔요」의 1 · 3 · 4번째와 스터디 경험을 UNKNOWN 으로 바꿔 두고 시작한다 — 2번째는 TEAM 그대로. 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class ActivityActorApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ActivityRepository activities;

    @Autowired
    AppUserRepository users;

    @Autowired
    EntityManager em;

    MockMvc mvc;
    long userId;
    long appId;
    long studyId;
    List<Long> appExperienceIds;
    long studyExperienceId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
        studyId = timeline.get(0).getId();
        appId = timeline.get(1).getId();
        appExperienceIds = timeline.get(1).getExperiences().stream().map(Experience::getId).toList();
        studyExperienceId = timeline.get(0).getExperiences().get(0).getId();

        setActor(appExperienceIds.get(0), "UNKNOWN");
        setActor(appExperienceIds.get(2), "UNKNOWN");
        setActor(appExperienceIds.get(3), "UNKNOWN");
        setActor(studyExperienceId, "UNKNOWN");
        // 4번째는 외부 AI 가 고쳐 확인 전인 경험으로 둔다
        em.createNativeQuery("update experience set mcp_change_id = 777, mcp_client = 'claude-ai' where id = :id")
                .setParameter("id", appExperienceIds.get(3)).executeUpdate();
        em.clear();
    }

    @Test
    void UNKNOWN_경험만_바꾸고_바뀐_개수를_돌려준다() throws Exception {
        decide(appId, "{\"actor\":\"ME\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(3));
        em.clear();

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].actor").value("ME"))
                .andExpect(jsonPath("$.experiences[1].actor").value("TEAM"))
                .andExpect(jsonPath("$.experiences[2].actor").value("ME"))
                .andExpect(jsonPath("$.experiences[3].actor").value("ME"));
    }

    @Test
    void TEAM_으로도_정한다() throws Exception {
        decide(appId, "{\"actor\":\"TEAM\"}").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(3));
        em.clear();

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].actor").value("TEAM"))
                .andExpect(jsonPath("$.experiences[3].actor").value("TEAM"));
    }

    @Test
    void 출처_상태와_외부_AI_확인_표시는_그대로다() throws Exception {
        String before = mvc.perform(get("/api/activities/{id}", appId)).andReturn().getResponse().getContentAsString();

        decide(appId, "{\"actor\":\"ME\"}").andExpect(status().isOk());
        em.clear();

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].sourceStatus")
                        .value((String) JsonPath.read(before, "$.experiences[0].sourceStatus")))
                .andExpect(jsonPath("$.experiences[2].sourceStatus")
                        .value((String) JsonPath.read(before, "$.experiences[2].sourceStatus")))
                .andExpect(jsonPath("$.experiences[3].sourceStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.experiences[3].mcpChange.changeId").value(777))
                .andExpect(jsonPath("$.experiences[3].mcpChange.client").value("claude-ai"));
    }

    @Test
    void 다시_보내면_바꿀_것이_없어_0() throws Exception {
        decide(appId, "{\"actor\":\"ME\"}").andExpect(jsonPath("$.changed").value(3));
        decide(appId, "{\"actor\":\"TEAM\"}").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(0));
        em.clear();

        mvc.perform(get("/api/activities/{id}", appId)).andExpect(jsonPath("$.experiences[0].actor").value("ME"));
    }

    @Test
    void 다른_활동의_UNKNOWN_경험은_그대로다() throws Exception {
        decide(appId, "{\"actor\":\"ME\"}").andExpect(status().isOk());
        em.clear();

        mvc.perform(get("/api/activities/{id}", studyId)).andExpect(jsonPath("$.experiences[0].actor").value("UNKNOWN"));
    }

    @Test
    void 분석_횟수를_세지_않는다() throws Exception {
        decide(appId, "{\"actor\":\"ME\"}").andExpect(status().isOk());

        mvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.dailyAnalysis.used").value(0));
    }

    @Test
    void actor_가_없거나_정하는_값이_아니면_400_이고_바뀌지_않는다() throws Exception {
        for (String body : List.of("{}", "{\"actor\":null}", "{\"actor\":\"UNKNOWN\"}", "{\"actor\":\"me\"}",
                "{\"actor\":\"BOSS\"}", "{\"actor\":1}")) {
            expectError(decide(appId, body), 400, "INVALID_REQUEST");
        }
        expectError(mvc.perform(post("/api/activities/{id}/actor", appId).contentType(MediaType.APPLICATION_JSON)),
                400, "INVALID_REQUEST");
        em.clear();

        mvc.perform(get("/api/activities/{id}", appId)).andExpect(jsonPath("$.experiences[0].actor").value("UNKNOWN"));
    }

    @Test
    void 없는_활동과_남의_활동은_404_이고_바뀌지_않는다() throws Exception {
        expectError(decide(999999L, "{\"actor\":\"ME\"}"), 404, "ACTIVITY_NOT_FOUND");

        long otherUserId = users.saveAndFlush(new AppUser("actor-other", "hash", null)).getId();
        expectError(TestLogin.mvc(context, otherUserId).perform(post("/api/activities/{id}/actor", appId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"ME\"}")), 404, "ACTIVITY_NOT_FOUND");
        em.clear();

        mvc.perform(get("/api/activities/{id}", appId)).andExpect(jsonPath("$.experiences[0].actor").value("UNKNOWN"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        expectError(MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/activities/{id}/actor", appId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"ME\"}")), 401, "UNAUTHENTICATED");
    }

    private void setActor(long experienceId, String actor) {
        em.createNativeQuery("update experience set actor = :actor where id = :id")
                .setParameter("actor", actor).setParameter("id", experienceId).executeUpdate();
    }

    private ResultActions decide(long activityId, String body) throws Exception {
        return mvc.perform(post("/api/activities/{id}/actor", activityId).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}

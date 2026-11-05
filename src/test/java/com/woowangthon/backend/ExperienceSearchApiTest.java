package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityCategory;
import com.woowangthon.backend.activity.ActivityRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/experience-api.md 「GET /api/experiences」(S-58, BE#96).
 * 검색 규칙(낱말 OR · 찾는 곳 · 역량 · 연도 · 순서 · limit · total)은 MCP search_experiences 와 같은 ExperienceSearch 가 돌리고,
 * 그 시험은 McpApiTest 「search_experiences」에 있다. 여기선 앱에만 있는 것 — 쿼리 읽기 · 출처 상태 · 활동 분류 · 400 · 401 ·
 * 응답 모양 — 과 대표 경우만 본다. 「MCP 와 같은 결과」는 McpApiTest 에 있다.
 * 시드(테스트 계정): 「바꿔요」(2025-03 ~ 06) 경험 4개(WebSocket · 회고 · 배포 · API 설계=확인 필요) · 「알고리즘 스터디」(2024-09 ~ 진행 중) 1개.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class ExperienceSearchApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ActivityRepository activities;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    long userId;
    long appId;
    long studyId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
        studyId = timeline.get(0).getId();
        appId = timeline.get(1).getId();
    }

    @Test
    void 조건이_없으면_경험_전부이고_활동_시작이_최근인_것부터() throws Exception {
        mvc.perform(get("/api/experiences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.experiences.length()").value(5))
                .andExpect(jsonPath("$.experiences[0].activity.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.experiences[4].activity.name").value("알고리즘 스터디"));
    }

    @Test
    void 경험은_계약의_모양에_activityId_대신_activity_와_분류를_싣는다() throws Exception {
        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);

        mvc.perform(get("/api/experiences").param("query", "WebSocket"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.experiences[0].content").value("채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다"))
                .andExpect(jsonPath("$.experiences[0].competencies[0]").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.experiences[0].competenciesBy").value("AI"))
                .andExpect(jsonPath("$.experiences[0].actor").value("ME"))
                .andExpect(jsonPath("$.experiences[0].sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.experiences[0].citations[0].sourceName").value("바꿔요-회고.md"))
                .andExpect(jsonPath("$.experiences[0].supplements.length()").value(0))
                .andExpect(jsonPath("$.experiences[0].activity.id").value(appId))
                .andExpect(jsonPath("$.experiences[0].activity.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.experiences[0].activity.period.start").value("2025-03"))
                .andExpect(jsonPath("$.experiences[0].activity.category").value("PROJECT"))
                // activityId 대신 activity · usedIn 은 활동 세부에만 · categoryBy 는 활동 쪽 칸이라 싣지 않는다
                .andExpect(jsonPath("$.experiences[0].activityId").doesNotExist())
                .andExpect(jsonPath("$.experiences[0].usedIn").doesNotExist())
                .andExpect(jsonPath("$.experiences[0].activity.categoryBy").doesNotExist());
    }

    @Test
    void 쉼표로_준_역량은_하나라도_붙은_경험이고_빈_칸은_건너뛴다() throws Exception {
        mvc.perform(get("/api/experiences").param("competencies", "COLLABORATION,OPERATION"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/experiences").param("competencies", " COLLABORATION, ,"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.experiences[0].content").value("매주 금요일 스프린트 회고를 했다"));
    }

    @Test
    void 출처_상태로_거른다() throws Exception {
        mvc.perform(get("/api/experiences").param("sourceStatus", "NEEDS_REVIEW"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.experiences[0].content").value("백엔드 API 설계를 맡았다"));
        mvc.perform(get("/api/experiences").param("sourceStatus", "SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/experiences").param("sourceStatus", "USER_ENTERED"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 분류로_거르면_미분류_활동의_경험은_빠진다() throws Exception {
        mvc.perform(get("/api/experiences").param("category", "PROJECT"))
                .andExpect(jsonPath("$.total").value(0));

        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);
        mvc.perform(get("/api/experiences").param("category", "PROJECT"))
                .andExpect(jsonPath("$.total").value(4));
        // 스터디는 미분류 — 분류를 주면 어느 분류로도 안 나오고, 「전체」(분류 없이)에만 나온다
        mvc.perform(get("/api/experiences").param("category", "STUDY"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/experiences")).andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void 조건은_모두_그리고로_묶는다() throws Exception {
        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);

        mvc.perform(get("/api/experiences").param("query", "websocket").param("sourceStatus", "NEEDS_REVIEW"))
                .andExpect(jsonPath("$.total").value(0));
        String body = mvc.perform(get("/api/experiences")
                        .param("query", "배포 websocket 문제")
                        .param("competencies", "IMPLEMENTATION")
                        .param("year", "2025")
                        .param("sourceStatus", "SOURCE_CONFIRMED")
                        .param("category", "PROJECT"))
                .andReturn().getResponse().getContentAsString();
        List<String> contents = JsonPath.read(body, "$.experiences[*].content");
        // WebSocket 경험은 「websocket」 「문제」 둘이 맞아 먼저, 배포는 하나
        assertThat(contents).containsExactly("채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다", "서비스를 AWS 에 배포했다");
    }

    @Test
    void limit_은_잘라_보내고_total_은_자르기_전_개수() throws Exception {
        mvc.perform(get("/api/experiences").param("limit", "2"))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.experiences.length()").value(2));
        mvc.perform(get("/api/experiences").param("limit", "200"))
                .andExpect(jsonPath("$.experiences.length()").value(5));
    }

    @Test
    void 맞는_게_없으면_200_과_빈_목록() throws Exception {
        mvc.perform(get("/api/experiences").param("query", "쿠버네티스"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.experiences.length()").value(0));
    }

    @Test
    void 빈_값으로_보낸_칸은_거르지_않는다() throws Exception {
        mvc.perform(get("/api/experiences").param("query", "  ").param("competencies", "").param("year", "")
                        .param("sourceStatus", "").param("category", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void 목록_밖_값_틀린_연도_범위_밖_limit_은_400() throws Exception {
        String[][] bad = {
                {"competencies", "협업"}, {"competencies", "COLLABORATION,TEAMWORK"},
                {"sourceStatus", "CONFIRMED"}, {"sourceStatus", "needs_review"},
                {"category", "project"}, {"category", "SPORTS"},
                {"year", "25"}, {"year", "2025-03"},
                {"limit", "0"}, {"limit", "201"}, {"limit", "많이"},
        };
        for (String[] pair : bad) {
            expectError(mvc.perform(get("/api/experiences").param(pair[0], pair[1])), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
        expectError(anonymous.perform(get("/api/experiences")), 401, "UNAUTHENTICATED");
    }

    @Test
    void 다른_사용자의_경험은_나오지_않는다() throws Exception {
        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        TestLogin.mvc(context, other).perform(get("/api/experiences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.experiences.length()").value(0));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

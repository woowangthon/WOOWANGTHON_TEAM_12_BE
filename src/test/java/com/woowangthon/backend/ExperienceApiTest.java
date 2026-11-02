package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.seed.SampleSeeder;
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
 * ETC docs/contracts/experience-api.md 의 활동 목록 · 세부 · 활동 수정 · 경험 수정.
 * 시드(계약 예시 데이터)를 켜고 돌린다. 테스트마다 롤백해 시드 상태에서 시작한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.seed-sample=true")
@Transactional
class ExperienceApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ActivityRepository activities;

    @Autowired
    SampleSeeder seeder;

    MockMvc mvc;
    long studyId;
    long appId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        List<Activity> timeline = activities.findTimeline(CurrentUser.id());
        studyId = timeline.get(0).getId();
        appId = timeline.get(1).getId();
    }

    // --- 활동 목록 ---

    @Test
    void 활동_목록은_시작_오름차순이고_경험_수가_붙는다() throws Exception {
        mvc.perform(get("/api/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities.length()").value(2))
                .andExpect(jsonPath("$.activities[0].name").value("알고리즘 스터디"))
                .andExpect(jsonPath("$.activities[0].period.start").value("2024-09"))
                .andExpect(jsonPath("$.activities[0].period.end").value(nullValue()))
                .andExpect(jsonPath("$.activities[0].period.ongoing").value(true))
                .andExpect(jsonPath("$.activities[0].experienceCount").value(1))
                .andExpect(jsonPath("$.activities[1].name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.activities[1].experienceCount").value(4));
    }

    @Test
    void 시작을_모르는_활동은_맨_뒤에_온다() throws Exception {
        activities.save(new Activity(CurrentUser.id(), "기간 모름", new Period(null, null, false)));
        activities.save(new Activity(CurrentUser.id(), "옛날 활동", new Period("2020", null, false)));

        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities.length()").value(4))
                .andExpect(jsonPath("$.activities[0].name").value("옛날 활동"))
                .andExpect(jsonPath("$.activities[3].name").value("기간 모름"))
                .andExpect(jsonPath("$.activities[3].period.start").value(nullValue()));
    }

    // --- 활동 세부 ---

    @Test
    void 활동_세부는_경험과_근거를_순서대로_준다() throws Exception {
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId))
                .andExpect(jsonPath("$.period.start").value("2025-03"))
                .andExpect(jsonPath("$.experiences.length()").value(4))
                .andExpect(jsonPath("$.experiences[0].activityId").value(appId))
                .andExpect(jsonPath("$.experiences[0].perspective").value("문제 해결"))
                .andExpect(jsonPath("$.experiences[0].actor").value("ME"))
                .andExpect(jsonPath("$.experiences[0].sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.experiences[0].citations[0].sourceName").value("바꿔요-회고.md"))
                .andExpect(jsonPath("$.experiences[0].citations[0].matched").value(true))
                .andExpect(jsonPath("$.experiences[0].citations[0].location.startLine").value(5))
                .andExpect(jsonPath("$.experiences[1].actor").value("TEAM"))
                .andExpect(jsonPath("$.experiences[3].sourceStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.experiences[3].citations[0].matched").value(false))
                .andExpect(jsonPath("$.experiences[3].citations[0].location").value(nullValue()));
    }

    @Test
    void 없는_활동은_404() throws Exception {
        expectError(mvc.perform(get("/api/activities/{id}", 999_999)), 404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 숫자가_아닌_id_는_400() throws Exception {
        expectError(mvc.perform(get("/api/activities/abc")), 400, "INVALID_REQUEST");
    }

    @Test
    void 스프링_기본_오류도_같은_오류_모양이다() throws Exception {
        expectError(mvc.perform(get("/api/nothing")), 404, "NOT_FOUND");
        expectError(mvc.perform(delete("/api/activities/{id}", appId)), 405, "METHOD_NOT_ALLOWED");
        expectError(mvc.perform(patch("/api/activities/{id}", appId).content("{}")), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    // --- 활동 수정 ---

    @Test
    void 활동_이름과_기간을_고치면_남는다() throws Exception {
        patchJson("/api/activities/" + appId, """
                {"name": "바꿔요 — 캠퍼스 중고거래 앱 (캡스톤)",
                 "period": {"start": "2025-03", "end": "2025-07", "ongoing": false}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("바꿔요 — 캠퍼스 중고거래 앱 (캡스톤)"))
                .andExpect(jsonPath("$.period.end").value("2025-07"))
                .andExpect(jsonPath("$.experienceCount").value(4));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.name").value("바꿔요 — 캠퍼스 중고거래 앱 (캡스톤)"))
                .andExpect(jsonPath("$.period.end").value("2025-07"));
    }

    @Test
    void 보낸_필드만_바뀐다() throws Exception {
        patchJson("/api/activities/" + appId, """
                {"name": "새 이름"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.period.start").value("2025-03"))
                .andExpect(jsonPath("$.period.end").value("2025-06"));
    }

    @Test
    void 정밀도가_다른_기간도_같은_해_안이면_된다() throws Exception {
        patchJson("/api/activities/" + appId, """
                {"period": {"start": "2025-03", "end": "2025", "ongoing": false}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.end").value("2025"));
    }

    @Test
    void 활동_수정_오류() throws Exception {
        String url = "/api/activities/" + appId;
        expectError(patchJson(url, """
                {"name": "  "}"""), 400, "INVALID_REQUEST");
        expectError(patchJson(url, """
                {"period": {"start": "2025-13", "end": null, "ongoing": false}}"""), 400, "INVALID_REQUEST");
        expectError(patchJson(url, """
                {"period": {"start": "2025-02-30", "end": null, "ongoing": false}}"""), 400, "INVALID_REQUEST");
        expectError(patchJson(url, """
                {"period": {"start": "2025-03", "end": null}}"""), 400, "INVALID_REQUEST");
        expectError(patchJson(url, """
                {"period": {"start": "2025-07", "end": "2025-03", "ongoing": false}}"""), 400, "INVALID_PERIOD");
        expectError(patchJson(url, """
                {"period": {"start": "2025-03", "end": "2025-06", "ongoing": true}}"""), 400, "INVALID_PERIOD");
        expectError(patchJson(url, "{not json"), 400, "INVALID_REQUEST");
        expectError(patchJson("/api/activities/999999", """
                {"name": "x"}"""), 404, "ACTIVITY_NOT_FOUND");
    }

    // --- 경험 수정 ---

    @Test
    void 경험을_고치면_내가_입력이_되고_근거는_그대로다() throws Exception {
        long experienceId = firstExperienceId(appId, 3);

        patchJson("/api/experiences/" + experienceId, """
                {"content": "백엔드 API 중 채팅 · 거래 API 를 설계했다", "actor": "ME"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(experienceId))
                .andExpect(jsonPath("$.content").value("백엔드 API 중 채팅 · 거래 API 를 설계했다"))
                .andExpect(jsonPath("$.perspective").value("기술 구현"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].quote").value("나는 백엔드 API 설계를 전부 맡았다."))
                .andExpect(jsonPath("$.citations[0].matched").value(false));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[3].sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.experiences[3].citations[0].quote").value("나는 백엔드 API 설계를 전부 맡았다."));
    }

    @Test
    void 값이_같으면_출처_상태가_바뀌지_않는다() throws Exception {
        long experienceId = firstExperienceId(appId, 0);

        patchJson("/api/experiences/" + experienceId, """
                {"actor": "ME", "perspective": "문제 해결"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"));
    }

    @Test
    void 관점은_null_로_지울_수_있다() throws Exception {
        long experienceId = firstExperienceId(appId, 1);

        patchJson("/api/experiences/" + experienceId, """
                {"perspective": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perspective").value(nullValue()))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"));
    }

    @Test
    void 경험_수정_오류() throws Exception {
        String url = "/api/experiences/" + firstExperienceId(appId, 0);
        expectError(patchJson(url, """
                {"content": ""}"""), 400, "INVALID_REQUEST");
        expectError(patchJson(url, """
                {"actor": "BOSS"}"""), 400, "INVALID_REQUEST");
        expectError(patchJson(url, """
                {"content": 3}"""), 400, "INVALID_REQUEST");
        expectError(patchJson("/api/experiences/999999", """
                {"content": "x"}"""), 404, "EXPERIENCE_NOT_FOUND");
    }

    // --- 시드 · CORS ---

    @Test
    void 시드는_다시_돌려도_늘어나지_않는다() throws Exception {
        seeder.run(null);
        assertThat(activities.findTimeline(CurrentUser.id())).hasSize(2);
    }

    @Test
    void 로컬_FE_주소에서_부를_수_있다() throws Exception {
        mvc.perform(options("/api/activities/1")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void 허용하지_않은_주소는_막힌다() throws Exception {
        mvc.perform(options("/api/activities")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    // --- 도움 ---

    private ResultActions patchJson(String url, String json) throws Exception {
        return mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }

    private long firstExperienceId(long activityId, int index) {
        return activities.findById(activityId).orElseThrow().getExperiences().get(index).getId();
    }
}

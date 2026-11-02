package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.seed.SampleSeeder;
import com.woowangthon.backend.source.SourceRepository;
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
 * ETC docs/contracts/experience-api.md 의 활동 목록 · 세부 · 활동 수정 · 경험 수정 · 직접 추가 · 지우기.
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

    @Autowired
    CitationRepository citations;

    @Autowired
    SourceRepository sources;

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
                .andExpect(jsonPath("$.experiences[0].competencies[0]").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.experiences[0].competencies[1]").value("IMPLEMENTATION"))
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
                .andExpect(jsonPath("$.competencies[0]").value("IMPLEMENTATION"))
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
                {"actor": "ME", "competencies": ["PROBLEM_SOLVING", "IMPLEMENTATION"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"));
    }

    @Test
    void 역량은_빈_배열로_지울_수_있다() throws Exception {
        long experienceId = firstExperienceId(appId, 1);

        patchJson("/api/experiences/" + experienceId, """
                {"competencies": []}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies.length()").value(0))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"));
    }

    @Test
    void 역량은_통째로_바뀌고_순서만_달라도_바뀐_것이다() throws Exception {
        long experienceId = firstExperienceId(appId, 0);

        patchJson("/api/experiences/" + experienceId, """
                {"competencies": ["IMPLEMENTATION", "PROBLEM_SOLVING"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies[0]").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.competencies[1]").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].competencies[0]").value("IMPLEMENTATION"));
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
        expectBadCompetencies(this::patchJson, url, "");
        expectError(patchJson("/api/experiences/999999", """
                {"content": "x"}"""), 404, "EXPERIENCE_NOT_FOUND");
    }

    // --- 경험 직접 추가 ---

    @Test
    void 직접_더한_경험은_내가_입력이고_근거가_없고_맨_뒤에_붙는다() throws Exception {
        postJson("/api/activities/" + appId + "/experiences", """
                {"content": "결제 실패 알림을 슬랙으로 받게 붙였다", "competencies": ["OPERATION"], "actor": "ME"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.activityId").value(appId))
                .andExpect(jsonPath("$.content").value("결제 실패 알림을 슬랙으로 받게 붙였다"))
                .andExpect(jsonPath("$.competencies.length()").value(1))
                .andExpect(jsonPath("$.competencies[0]").value("OPERATION"))
                .andExpect(jsonPath("$.actor").value("ME"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.citations.length()").value(0));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(5))
                .andExpect(jsonPath("$.experiences[4].content").value("결제 실패 알림을 슬랙으로 받게 붙였다"))
                .andExpect(jsonPath("$.experiences[4].sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.experiences[4].citations.length()").value(0));
    }

    @Test
    void 역량을_빼면_빈_배열이고_경험이_없는_활동에도_더할_수_있다() throws Exception {
        long emptyId = activities.save(new Activity(CurrentUser.id(), "빈 활동", new Period(null, null, false))).getId();

        postJson("/api/activities/" + emptyId + "/experiences", """
                {"content": "첫 경험", "actor": "UNKNOWN"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.competencies.length()").value(0))
                .andExpect(jsonPath("$.actor").value("UNKNOWN"));
        postJson("/api/activities/" + emptyId + "/experiences", """
                {"content": "둘째 경험", "actor": "TEAM"}
                """)
                .andExpect(status().isCreated());

        mvc.perform(get("/api/activities/{id}", emptyId))
                .andExpect(jsonPath("$.experiences.length()").value(2))
                .andExpect(jsonPath("$.experiences[0].content").value("첫 경험"))
                .andExpect(jsonPath("$.experiences[1].content").value("둘째 경험"));
    }

    @Test
    void 경험_직접_추가_오류() throws Exception {
        String url = "/api/activities/" + appId + "/experiences";
        expectError(postJson(url, """
                {"content": "  ", "actor": "ME"}"""), 400, "INVALID_REQUEST");
        expectError(postJson(url, """
                {"actor": "ME"}"""), 400, "INVALID_REQUEST");
        expectError(postJson(url, """
                {"content": "x"}"""), 400, "INVALID_REQUEST");
        expectError(postJson(url, """
                {"content": "x", "actor": "BOSS"}"""), 400, "INVALID_REQUEST");
        expectError(postJson(url, """
                {"content": 3, "actor": "ME"}"""), 400, "INVALID_REQUEST");
        expectError(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)), 400, "INVALID_REQUEST");
        expectBadCompetencies(this::postJson, url, "\"content\": \"x\", \"actor\": \"ME\", ");
        expectError(postJson("/api/activities/999999/experiences", """
                {"content": "x", "actor": "ME"}"""), 404, "ACTIVITY_NOT_FOUND");

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(4));
    }

    // --- 경험 지우기 ---

    @Test
    void 경험을_지우면_근거도_사라지고_원본은_남는다() throws Exception {
        Experience target = activities.findById(appId).orElseThrow().getExperiences().get(0);
        long experienceId = target.getId();
        long citationId = target.getCitations().get(0).getId();
        long sourceId = target.getCitations().get(0).getSource().getId();

        mvc.perform(delete("/api/experiences/{id}", experienceId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(3))
                .andExpect(jsonPath("$.experiences[0].id").value(
                        activities.findById(appId).orElseThrow().getExperiences().get(0).getId()));
        assertThat(citations.findById(citationId)).isEmpty();
        assertThat(sources.findById(sourceId)).isPresent();
        expectError(mvc.perform(delete("/api/experiences/{id}", experienceId)), 404, "EXPERIENCE_NOT_FOUND");
    }

    @Test
    void 경험이_다_빠진_활동도_남는다() throws Exception {
        long experienceId = firstExperienceId(studyId, 0);

        mvc.perform(delete("/api/experiences/{id}", experienceId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/activities/{id}", studyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences.length()").value(0));
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities.length()").value(2));
    }

    @Test
    void 없는_경험을_지우면_404() throws Exception {
        expectError(mvc.perform(delete("/api/experiences/{id}", 999_999)), 404, "EXPERIENCE_NOT_FOUND");
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
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
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

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    interface JsonCall {
        ResultActions call(String url, String json) throws Exception;
    }

    // 계약: 목록 밖 값 · 중복 · 4개 이상 · null · 배열 아님 → 400
    private static void expectBadCompetencies(JsonCall call, String url, String otherFields) throws Exception {
        for (String competencies : List.of("[\"FRIENDSHIP\"]", "[\"협업\"]", "[\"PLANNING\", \"PLANNING\"]",
                "[\"PLANNING\", \"ANALYSIS\", \"LEARNING\", \"OPERATION\"]", "null", "\"PLANNING\"", "[3]")) {
            expectError(call.call(url, "{" + otherFields + "\"competencies\": " + competencies + "}"),
                    400, "INVALID_REQUEST");
        }
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

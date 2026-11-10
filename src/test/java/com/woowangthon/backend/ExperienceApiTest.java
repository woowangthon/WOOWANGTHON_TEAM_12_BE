package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityCategory;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/experience-api.md 의 활동 목록 · 세부 · 활동 수정 · 경험 수정 · 직접 추가 · 지우기 · 옮기기 · 합치기.
 * 시드(계약 예시 데이터)를 켜고 테스트 계정으로 돌린다. 테스트마다 롤백해 시드 상태에서 시작한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    MockMvc mvc;
    long userId;
    long studyId;
    long appId;

    @BeforeEach
    void setUp() {
        // 시드는 테스트 계정에 들어간다
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
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
        activities.save(new Activity(userId, "기간 모름", new Period(null, null, false)));
        activities.save(new Activity(userId, "옛날 활동", new Period("2020", null, false)));

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
                .andExpect(jsonPath("$.experiences[0].competenciesBy").value("AI"))
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
        expectError(mvc.perform(put("/api/activities/{id}", appId)), 405, "METHOD_NOT_ALLOWED");
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
                .andExpect(jsonPath("$.competenciesBy").value("AI"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].quote").value("나는 백엔드 API 설계를 전부 맡았다."))
                .andExpect(jsonPath("$.citations[0].matched").value(false));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[3].sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.experiences[3].competenciesBy").value("AI"))
                .andExpect(jsonPath("$.experiences[3].citations[0].quote").value("나는 백엔드 API 설계를 전부 맡았다."));
    }

    @Test
    void 값이_같으면_출처_상태가_바뀌지_않는다() throws Exception {
        long experienceId = firstExperienceId(appId, 0);

        patchJson("/api/experiences/" + experienceId, """
                {"actor": "ME", "competencies": ["PROBLEM_SOLVING", "IMPLEMENTATION"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.competenciesBy").value("AI"));
    }

    @Test
    void 역량을_빈_배열로_지워도_출처_상태는_그대로고_역량_출처만_USER_가_된다() throws Exception {
        long experienceId = firstExperienceId(appId, 1);
        String statusBefore = statusOf(experienceId);

        patchJson("/api/experiences/" + experienceId, """
                {"competencies": []}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies.length()").value(0))
                .andExpect(jsonPath("$.competenciesBy").value("USER"))
                .andExpect(jsonPath("$.sourceStatus").value(statusBefore));
    }

    @Test
    void 역량은_통째로_바뀌고_순서만_달라도_역량_출처가_USER_가_되며_출처_상태는_그대로다() throws Exception {
        long experienceId = firstExperienceId(appId, 0);

        patchJson("/api/experiences/" + experienceId, """
                {"competencies": ["IMPLEMENTATION", "PROBLEM_SOLVING"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies[0]").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.competencies[1]").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.competenciesBy").value("USER"))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].competencies[0]").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.experiences[0].competenciesBy").value("USER"));

        // 그 뒤 본문만 고쳐도 USER 는 그대로다
        patchJson("/api/experiences/" + experienceId, """
                {"content": "채팅 API 의 재연결 문제를 고쳤다"}
                """)
                .andExpect(jsonPath("$.competenciesBy").value("USER"));
    }

    @Test
    void 주체만_바꾸면_SOURCE_CONFIRMED_와_NEEDS_REVIEW_가_그대로다() throws Exception {
        long confirmed = firstExperienceId(appId, 0);
        long review = firstExperienceId(appId, 3);

        patchJson("/api/experiences/" + confirmed, "{\"actor\": \"TEAM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("TEAM"))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.competenciesBy").value("AI"));
        patchJson("/api/experiences/" + review, "{\"actor\": \"TEAM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("TEAM"))
                .andExpect(jsonPath("$.sourceStatus").value("NEEDS_REVIEW"));
    }

    @Test
    void 본문을_바꾸면_USER_ENTERED_이고_본문과_주체를_함께_바꿔도_같다() throws Exception {
        long first = firstExperienceId(appId, 0);
        long second = firstExperienceId(appId, 3);

        patchJson("/api/experiences/" + first, "{\"content\": \"본문만 고침\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"));
        patchJson("/api/experiences/" + second, "{\"content\": \"본문과 주체를 고침\", \"actor\": \"TEAM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("TEAM"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"));
    }

    @Test
    void NEEDS_REVIEW_경험은_주체나_역량을_바꿔도_풀리지_않는다() throws Exception {
        long experienceId = firstExperienceId(appId, 3);

        patchJson("/api/experiences/" + experienceId, "{\"actor\": \"TEAM\"}")
                .andExpect(jsonPath("$.sourceStatus").value("NEEDS_REVIEW"));
        patchJson("/api/experiences/" + experienceId, "{\"competencies\": [\"COLLABORATION\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competenciesBy").value("USER"))
                .andExpect(jsonPath("$.sourceStatus").value("NEEDS_REVIEW"));
    }

    @Test
    void 보낸_역량_출처는_받지_않는다() throws Exception {
        patchJson("/api/experiences/" + firstExperienceId(appId, 0), """
                {"competenciesBy": "USER"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competenciesBy").value("AI"))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"));
        postJson("/api/activities/" + appId + "/experiences", """
                {"content": "직접 적은 경험", "actor": "ME", "competenciesBy": "AI"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.competenciesBy").value("USER"));
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
                .andExpect(jsonPath("$.competenciesBy").value("USER"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.citations.length()").value(0));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(5))
                .andExpect(jsonPath("$.experiences[4].content").value("결제 실패 알림을 슬랙으로 받게 붙였다"))
                .andExpect(jsonPath("$.experiences[4].sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.experiences[4].competenciesBy").value("USER"))
                .andExpect(jsonPath("$.experiences[4].citations.length()").value(0));
    }

    @Test
    void 역량을_빼면_빈_배열이고_경험이_없는_활동에도_더할_수_있다() throws Exception {
        long emptyId = activities.save(new Activity(userId, "빈 활동", new Period(null, null, false))).getId();

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

    // --- 경험 옮기기 ---

    @Test
    void 옮긴_경험은_새_활동_맨_뒤에_붙고_내용_근거_출처_상태는_그대로다() throws Exception {
        long experienceId = firstExperienceId(appId, 1);

        postJson("/api/experiences/" + experienceId + "/move", "{\"toActivityId\": " + studyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(experienceId))
                .andExpect(jsonPath("$.activityId").value(studyId))
                .andExpect(jsonPath("$.content").value("매주 금요일 스프린트 회고를 했다"))
                .andExpect(jsonPath("$.competencies[0]").value("COLLABORATION"))
                .andExpect(jsonPath("$.competenciesBy").value("AI"))
                .andExpect(jsonPath("$.actor").value("TEAM"))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].quote").value("팀은 매주 금요일 스프린트 회고를 했다."));

        mvc.perform(get("/api/activities/{id}", studyId))
                .andExpect(jsonPath("$.experiences.length()").value(2))
                .andExpect(jsonPath("$.experiences[1].id").value(experienceId))
                .andExpect(jsonPath("$.experiences[1].activityId").value(studyId))
                .andExpect(jsonPath("$.experiences[1].sourceStatus").value("SOURCE_CONFIRMED"));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(3))
                .andExpect(jsonPath("$.experiences[*].id", not(hasItem((int) experienceId))));
    }

    @Test
    void 이미_그_활동에_있으면_그대로_200() throws Exception {
        long experienceId = firstExperienceId(appId, 0);

        postJson("/api/experiences/" + experienceId + "/move", "{\"toActivityId\": " + appId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(appId));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(4))
                .andExpect(jsonPath("$.experiences[0].id").value(experienceId));
    }

    @Test
    void 경험이_다_빠진_활동도_옮긴_뒤_남는다() throws Exception {
        postJson("/api/experiences/" + firstExperienceId(studyId, 0) + "/move", "{\"toActivityId\": " + appId + "}")
                .andExpect(status().isOk());

        mvc.perform(get("/api/activities/{id}", studyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences.length()").value(0));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(5));
    }

    @Test
    void 경험_옮기기_오류() throws Exception {
        String url = "/api/experiences/" + firstExperienceId(appId, 0) + "/move";
        expectError(postJson(url, "{}"), 400, "INVALID_REQUEST");
        expectError(postJson(url, "{\"toActivityId\": null}"), 400, "INVALID_REQUEST");
        expectError(postJson(url, "{\"toActivityId\": \"8\"}"), 400, "INVALID_REQUEST");
        expectError(postJson(url, "{\"toActivityId\": 1.5}"), 400, "INVALID_REQUEST");
        expectError(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)), 400, "INVALID_REQUEST");
        expectError(postJson(url, "{\"toActivityId\": 999999}"), 404, "ACTIVITY_NOT_FOUND");
        expectError(postJson("/api/experiences/999999/move", "{\"toActivityId\": " + studyId + "}"),
                404, "EXPERIENCE_NOT_FOUND");

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(4));
    }

    // --- 활동 합치기 ---

    @Test
    void 합치면_경험이_남길_활동_뒤에_모이고_다른_활동은_사라진다() throws Exception {
        Experience studyExperience = activities.findById(studyId).orElseThrow().getExperiences().get(0);
        long studyExperienceId = studyExperience.getId();
        long citationId = studyExperience.getCitations().get(0).getId();

        postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + studyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId))
                .andExpect(jsonPath("$.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.period.start").value("2025-03"))
                .andExpect(jsonPath("$.period.end").value("2025-06"))
                .andExpect(jsonPath("$.experiences.length()").value(5))
                .andExpect(jsonPath("$.experiences[4].id").value(studyExperienceId))
                .andExpect(jsonPath("$.experiences[4].activityId").value(appId))
                .andExpect(jsonPath("$.experiences[4].sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.experiences[4].citations[0].id").value(citationId));

        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(5))
                .andExpect(jsonPath("$.experiences[4].id").value(studyExperienceId));
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities.length()").value(1))
                .andExpect(jsonPath("$.activities[0].id").value(appId));
        expectError(mvc.perform(get("/api/activities/{id}", studyId)), 404, "ACTIVITY_NOT_FOUND");
        expectError(postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + studyId + "}"),
                404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 빈_활동도_합칠_수_있다() throws Exception {
        long emptyId = activities.save(new Activity(userId, "빈 활동", new Period(null, null, false))).getId();

        postJson("/api/activities/" + studyId + "/merge", "{\"fromActivityId\": " + emptyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences.length()").value(1));
        expectError(mvc.perform(get("/api/activities/{id}", emptyId)), 404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 활동_합치기_오류() throws Exception {
        String url = "/api/activities/" + appId + "/merge";
        expectError(postJson(url, "{}"), 400, "INVALID_REQUEST");
        expectError(postJson(url, "{\"fromActivityId\": \"x\"}"), 400, "INVALID_REQUEST");
        expectError(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)), 400, "INVALID_REQUEST");
        expectError(postJson(url, "{\"fromActivityId\": " + appId + "}"), 400, "SAME_ACTIVITY");
        expectError(postJson(url, "{\"fromActivityId\": 999999}"), 404, "ACTIVITY_NOT_FOUND");
        expectError(postJson("/api/activities/999999/merge", "{\"fromActivityId\": " + studyId + "}"),
                404, "ACTIVITY_NOT_FOUND");

        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities.length()").value(2));
    }

    // --- 같은 활동 제안(S-44) ---

    // 분석 작업이 붙이는 것과 같게 — 이력서를 넣어 생긴 새 활동이 기존 「바꿔요」를 가리킨다
    private long newActivitySuggesting(long targetId) {
        Activity created = new Activity(userId, "캡스톤 「바꿔요」", new Period("2025", "2025", false));
        created.suggestMergeWith(targetId);
        return activities.save(created).getId();
    }

    @Test
    void 제안은_목록과_세부에_기존_활동의_지금_이름_기간으로_실린다() throws Exception {
        long newId = newActivitySuggesting(appId);
        activities.findById(appId).orElseThrow().rename("바꿔요");

        mvc.perform(get("/api/activities"))
                // 시작 「2025」 는 「2025-03」 보다 앞선다
                .andExpect(jsonPath("$.activities[0].mergeSuggestion").value(nullValue()))
                .andExpect(jsonPath("$.activities[1].id").value(newId))
                .andExpect(jsonPath("$.activities[1].mergeSuggestion.activityId").value(appId))
                .andExpect(jsonPath("$.activities[1].mergeSuggestion.name").value("바꿔요"))
                .andExpect(jsonPath("$.activities[1].mergeSuggestion.period.start").value("2025-03"))
                .andExpect(jsonPath("$.activities[1].mergeSuggestion.period.end").value("2025-06"))
                .andExpect(jsonPath("$.activities[2].mergeSuggestion").value(nullValue()));
        mvc.perform(get("/api/activities/{id}", newId))
                .andExpect(jsonPath("$.mergeSuggestion.activityId").value(appId))
                .andExpect(jsonPath("$.mergeSuggestion.name").value("바꿔요"));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.mergeSuggestion").value(nullValue()));
        patchJson("/api/activities/" + newId, "{\"name\": \"캡스톤\"}")
                .andExpect(jsonPath("$.mergeSuggestion.activityId").value(appId));
    }

    @Test
    void 계기는_보낸_때만_바뀌고_null_빈_문자열이면_지워진다() throws Exception {
        // 처음엔 없다
        mvc.perform(get("/api/activities/{id}", appId)).andExpect(jsonPath("$.note").value(nullValue()));

        // 앞뒤 공백 · 줄바꿈은 걷고 저장한다
        patchJson("/api/activities/" + appId, "{\"note\": \"  문제만 풀다 보니 만들고 싶어졌다\\n\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("문제만 풀다 보니 만들고 싶어졌다"));
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[?(@.id == %d)].note".formatted(appId))
                        .value("문제만 풀다 보니 만들고 싶어졌다"))
                .andExpect(jsonPath("$.activities[?(@.id == %d)].note".formatted(studyId)).value(hasItem(nullValue())));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.note").value("문제만 풀다 보니 만들고 싶어졌다"));

        // 빼면 그대로
        patchJson("/api/activities/" + appId, "{\"name\": \"바꿔요\"}")
                .andExpect(jsonPath("$.note").value("문제만 풀다 보니 만들고 싶어졌다"));

        patchJson("/api/activities/" + appId, "{\"note\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(nullValue()));

        patchJson("/api/activities/" + appId, "{\"note\": \"다시\"}").andExpect(jsonPath("$.note").value("다시"));
        patchJson("/api/activities/" + appId, "{\"note\": \"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(nullValue()));
    }

    @Test
    void 계기_오류() throws Exception {
        String url = "/api/activities/" + appId;
        String max = "가".repeat(200);

        patchJson(url, "{\"note\": \"  " + max + "  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(max));

        for (String note : List.of("\"" + max + "가\"", "\"첫 줄\\n둘째 줄\"", "\"첫 줄\\r둘째 줄\"", "3", "true", "[]")) {
            expectError(patchJson(url, "{\"note\": " + note + "}"), 400, "INVALID_REQUEST");
        }
        mvc.perform(get(url)).andExpect(jsonPath("$.note").value(max));
    }

    @Test
    void 합치면_남길_활동의_계기를_두고_없을_때만_옮겨_온다() throws Exception {
        patchJson("/api/activities/" + studyId, "{\"note\": \"스터디 계기\"}").andExpect(status().isOk());

        // 남길 활동에 없으면 옮겨 온다
        long keepId = activities.save(new Activity(userId, "남길 활동", new Period(null, null, false))).getId();
        postJson("/api/activities/" + keepId + "/merge", "{\"fromActivityId\": " + studyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("스터디 계기"));

        // 있으면 그대로 — 이어 붙이지 않는다
        patchJson("/api/activities/" + appId, "{\"note\": \"앱 계기\"}").andExpect(status().isOk());
        postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + keepId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("앱 계기"));
    }

    // --- 활동 분류(S-57, BE#95) ---

    @Test
    void 활동_분류는_처음엔_미분류이고_목록_세부에_null_로_실린다() throws Exception {
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[0].category").value(nullValue()))
                .andExpect(jsonPath("$.activities[0].categoryBy").value(nullValue()))
                .andExpect(jsonPath("$.activities[1].category").value(nullValue()));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andExpect(jsonPath("$.categoryBy").value(nullValue()));

        // 분석이 붙인 분류는 AI 해석으로 실린다
        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[1].category").value("PROJECT"))
                .andExpect(jsonPath("$.activities[1].categoryBy").value("AI"));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.category").value("PROJECT"))
                .andExpect(jsonPath("$.categoryBy").value("AI"));
    }

    @Test
    void 활동_분류를_바꾸면_USER_같은_값이면_그대로_null_이면_비운다() throws Exception {
        activities.findById(appId).orElseThrow().categorizeByAi(ActivityCategory.PROJECT);
        String url = "/api/activities/" + appId;

        // 같은 값 — 출처도 그대로(AI 해석 표시가 남는다)
        patchJson(url, "{\"category\": \"PROJECT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("PROJECT"))
                .andExpect(jsonPath("$.categoryBy").value("AI"));
        // 안 보내면 그대로
        patchJson(url, "{\"name\": \"바꿔요\"}")
                .andExpect(jsonPath("$.category").value("PROJECT"))
                .andExpect(jsonPath("$.categoryBy").value("AI"));
        // 다른 값 — 사용자가 고른 것
        patchJson(url, "{\"category\": \"CLUB\"}")
                .andExpect(jsonPath("$.category").value("CLUB"))
                .andExpect(jsonPath("$.categoryBy").value("USER"));
        mvc.perform(get(url))
                .andExpect(jsonPath("$.category").value("CLUB"))
                .andExpect(jsonPath("$.categoryBy").value("USER"));
        // null — 둘 다 비운다(미분류)
        patchJson(url, "{\"category\": null}")
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andExpect(jsonPath("$.categoryBy").value(nullValue()));
        // 비운 뒤 다시 고르면 USER
        patchJson(url, "{\"category\": \"STUDY\"}")
                .andExpect(jsonPath("$.category").value("STUDY"))
                .andExpect(jsonPath("$.categoryBy").value("USER"));
    }

    @Test
    void 활동_분류가_목록_밖이면_400_이고_그대로다() throws Exception {
        String url = "/api/activities/" + appId;
        patchJson(url, "{\"category\": \"CLUB\"}").andExpect(status().isOk());
        expectError(patchJson(url, "{\"category\": \"SPORTS\"}"), 400, "INVALID_REQUEST");
        expectError(patchJson(url, "{\"category\": \"club\"}"), 400, "INVALID_REQUEST");
        expectError(patchJson(url, "{\"category\": 3}"), 400, "INVALID_REQUEST");
        expectError(patchJson(url, "{\"category\": \"\"}"), 400, "INVALID_REQUEST");
        mvc.perform(get(url))
                .andExpect(jsonPath("$.category").value("CLUB"))
                .andExpect(jsonPath("$.categoryBy").value("USER"));
    }

    @Test
    void 합치면_남길_활동이_미분류일_때만_분류를_옮겨_온다() throws Exception {
        activities.findById(studyId).orElseThrow().categorizeByAi(ActivityCategory.STUDY);

        // 남길 활동이 미분류면 출처까지 같이 옮겨 온다
        long keepId = activities.save(new Activity(userId, "남길 활동", new Period(null, null, false))).getId();
        postJson("/api/activities/" + keepId + "/merge", "{\"fromActivityId\": " + studyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("STUDY"))
                .andExpect(jsonPath("$.categoryBy").value("AI"));

        // 남길 활동에 분류가 있으면 그대로
        patchJson("/api/activities/" + appId, "{\"category\": \"PROJECT\"}").andExpect(status().isOk());
        postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + keepId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("PROJECT"))
                .andExpect(jsonPath("$.categoryBy").value("USER"));
    }

    @Test
    void 따로_두면_제안만_사라지고_두_번_눌러도_204() throws Exception {
        long newId = newActivitySuggesting(appId);

        mvc.perform(delete("/api/activities/{id}/merge-suggestion", newId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/activities/{id}", newId))
                .andExpect(jsonPath("$.name").value("캡스톤 「바꿔요」"))
                .andExpect(jsonPath("$.mergeSuggestion").value(nullValue()));
        mvc.perform(delete("/api/activities/{id}/merge-suggestion", newId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/activities")).andExpect(jsonPath("$.activities.length()").value(3));
    }

    @Test
    void 따로_두기_오류() throws Exception {
        expectError(mvc.perform(delete("/api/activities/{id}/merge-suggestion", 999_999)), 404, "ACTIVITY_NOT_FOUND");
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        Activity others = new Activity(otherId, "남의 활동", new Period(null, null, false));
        long othersId = activities.save(others).getId();
        expectError(mvc.perform(delete("/api/activities/{id}/merge-suggestion", othersId)), 404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 붙이면_기존_활동이_남고_제안은_사라진다() throws Exception {
        long newId = newActivitySuggesting(appId);

        postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + newId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId))
                .andExpect(jsonPath("$.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.mergeSuggestion").value(nullValue()));
        expectError(mvc.perform(get("/api/activities/{id}", newId)), 404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 가리키던_활동이_합쳐져_사라지면_제안은_null() throws Exception {
        long newId = newActivitySuggesting(studyId);
        Activity app = activities.findById(appId).orElseThrow();
        app.suggestMergeWith(studyId);
        long keptSuggestionId = newActivitySuggesting(appId);

        // 남길 활동이 사라질 활동을 가리키고 있었어도 null
        postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + studyId + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeSuggestion").value(nullValue()));

        // 벌크 UPDATE 라 테스트 트랜잭션의 엔티티가 아닌 DB 를 본다
        String suggestion = "select merge_suggestion_activity_id from activity where id = ?";
        assertThat(jdbc.queryForObject(suggestion, Long.class, newId)).isNull();
        assertThat(jdbc.queryForObject(suggestion, Long.class, keptSuggestionId)).isEqualTo(appId);
    }

    // 테스트 트랜잭션 밖에서 돌려 합치기 자체의 트랜잭션을 본다. 활동 DELETE 를 DB 트리거로 실패시킨다
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 합치기가_중간에_실패하면_아무것도_바뀌지_않는다() throws Exception {
        String experienceOwners = "select activity_id from experience order by id";
        List<Long> before = jdbc.queryForList(experienceOwners, Long.class);
        jdbc.execute("""
                create function fail_activity_delete() returns trigger language plpgsql as
                $$ begin raise exception 'test: activity delete blocked'; end $$""");
        jdbc.execute("""
                create trigger fail_activity_delete before delete on activity
                for each row execute function fail_activity_delete()""");
        try {
            postJson("/api/activities/" + appId + "/merge", "{\"fromActivityId\": " + studyId + "}")
                    .andExpect(status().isInternalServerError());

            assertThat(jdbc.queryForList(experienceOwners, Long.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from activity where id = ?", Long.class, studyId))
                    .isEqualTo(1L);
        } finally {
            jdbc.execute("drop trigger fail_activity_delete on activity");
            jdbc.execute("drop function fail_activity_delete()");
        }
    }

    // --- 시드 · CORS ---

    @Test
    void 시드는_다시_돌려도_늘어나지_않는다() throws Exception {
        seeder.run(null);
        assertThat(activities.findTimeline(userId)).hasSize(2);
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

    private String statusOf(long experienceId) {
        return jdbc.queryForObject("select source_status from experience where id = ?", String.class, experienceId);
    }

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

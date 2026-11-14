package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/experience-api.md — 사용자가 쓰는 글의 길이 한도: 경험 본문 5000자 · 활동 이름 100자 · 자료 이름 300자.
 * 시드(테스트 계정)로 돈다. 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class LengthLimitApiTest {

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
    long activityId;
    long experienceId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
        activityId = timeline.get(1).getId();
        experienceId = timeline.get(1).getExperiences().get(0).getId();
    }

    @Test
    void 경험_본문은_5000자까지_고치고_더할_수_있다() throws Exception {
        patchExperience("{\"content\": \"" + "가".repeat(5001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        patchExperience("{\"content\": \"" + "가".repeat(5000) + "\"}").andExpect(status().isOk());

        addExperience("가".repeat(5001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        addExperience("가".repeat(5000)).andExpect(status().isCreated());
    }

    @Test
    void 활동_이름은_100자까지() throws Exception {
        patchActivity("{\"name\": \"" + "가".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        patchActivity("{\"name\": \"" + "가".repeat(100) + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("가".repeat(100)));
    }

    @Test
    void 자료_이름이_300자를_넘으면_넣지_않고_횟수도_세지_않는다() throws Exception {
        int before = used();
        String name = "a".repeat(298) + ".md"; // 301자
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"FILE\", \"name\": \"" + name + "\", \"text\": \"자료 글\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/sources/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sources\": [{\"kind\": \"FILE\", \"name\": \"" + name + "\", \"text\": \"자료 글\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(used()).isEqualTo(before);
    }

    private int used() {
        return jdbc.query("select count from analysis_usage where user_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, userId);
    }

    private ResultActions patchExperience(String json) throws Exception {
        return mvc.perform(patch("/api/experiences/{id}", experienceId).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions addExperience(String content) throws Exception {
        return mvc.perform(post("/api/activities/{id}/experiences", activityId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"" + content + "\", \"actor\": \"ME\"}"));
    }

    private ResultActions patchActivity(String json) throws Exception {
        return mvc.perform(patch("/api/activities/{id}", activityId).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}

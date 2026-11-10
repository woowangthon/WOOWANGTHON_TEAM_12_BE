package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** ETC docs/contracts/experience-api.md 「DELETE /api/activities/{activityId} — 활동 지우기」(S-88). 시드로 돌리고 테스트마다 롤백한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class ActivityDeleteApiTest {

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
    long studyId;
    long appId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
        studyId = timeline.get(0).getId();
        appId = timeline.get(1).getId();
    }

    @Test
    void 지우면_활동_경험_근거_보완이_사라지고_원본은_남는다() throws Exception {
        List<Long> experienceIds = jdbc.queryForList("select id from experience where activity_id = ?", Long.class, appId);
        assertThat(experienceIds).hasSize(4);
        long experienceId = experienceIds.get(0);
        jdbc.update("insert into supplement (experience_id, question, answer) values (?, '몇 명이었나요?', '4명')", experienceId);
        List<Long> sourceIds = jdbc.queryForList("""
                select distinct c.source_id from citation c join experience e on e.id = c.experience_id
                where e.activity_id = ?""", Long.class, appId);
        assertThat(sourceIds).isNotEmpty();

        mvc.perform(delete("/api/activities/{id}", appId)).andExpect(status().isNoContent());

        expectError(mvc.perform(get("/api/activities/{id}", appId)), 404, "ACTIVITY_NOT_FOUND");
        expectError(mvc.perform(patch("/api/experiences/{id}", experienceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"고침\"}")), 404, "EXPERIENCE_NOT_FOUND");
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities.length()").value(1))
                .andExpect(jsonPath("$.activities[0].id").value(studyId));
        assertThat(count("select count(*) from experience where activity_id = " + appId)).isZero();
        assertThat(count("select count(*) from citation where experience_id in (" + join(experienceIds) + ")")).isZero();
        assertThat(count("select count(*) from supplement where experience_id = " + experienceId)).isZero();
        assertThat(count("select count(*) from source where id in (" + join(sourceIds) + ")")).isEqualTo(sourceIds.size());
        // 다른 활동은 그대로
        mvc.perform(get("/api/activities/{id}", studyId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences.length()").value(1));
    }

    @Test
    void 경험이_없는_활동도_지운다() throws Exception {
        long emptyId = activities.save(new Activity(userId, "빈 활동", new Period(null, null, false))).getId();

        mvc.perform(delete("/api/activities/{id}", emptyId)).andExpect(status().isNoContent());

        expectError(mvc.perform(get("/api/activities/{id}", emptyId)), 404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 없음_남의_것_이미_지운_활동은_404() throws Exception {
        expectError(mvc.perform(delete("/api/activities/{id}", 999_999)), 404, "ACTIVITY_NOT_FOUND");

        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        long othersId = activities.save(new Activity(otherId, "남의 활동", new Period(null, null, false))).getId();
        expectError(mvc.perform(delete("/api/activities/{id}", othersId)), 404, "ACTIVITY_NOT_FOUND");
        assertThat(activities.findById(othersId)).isPresent();

        mvc.perform(delete("/api/activities/{id}", studyId)).andExpect(status().isNoContent());
        expectError(mvc.perform(delete("/api/activities/{id}", studyId)), 404, "ACTIVITY_NOT_FOUND");
    }

    @Test
    void 지운_활동을_가리키던_제안은_null_이고_이어짐은_지워진다() throws Exception {
        Activity suggesting = new Activity(userId, "새 활동", new Period(null, null, false));
        suggesting.suggestMergeWith(appId);
        long suggestingId = activities.save(suggesting).getId();
        jdbc.update("insert into activity_link (from_activity_id, to_activity_id, reason, status) values (?, ?, '이어짐', 'CONFIRMED')",
                studyId, appId);
        jdbc.update("insert into activity_link (from_activity_id, to_activity_id, reason, status) values (?, ?, '이어짐', 'SUGGESTED')",
                appId, suggestingId);
        jdbc.update("insert into activity_link (from_activity_id, to_activity_id, reason, status) values (?, ?, '이어짐', 'SUGGESTED')",
                studyId, suggestingId);

        mvc.perform(delete("/api/activities/{id}", appId)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select merge_suggestion_activity_id from activity where id = ?", Long.class,
                suggestingId)).isNull();
        mvc.perform(get("/api/activities/{id}", suggestingId)).andExpect(status().isOk());
        assertThat(count("select count(*) from activity_link where from_activity_id = " + appId
                + " or to_activity_id = " + appId)).isZero();
        assertThat(count("select count(*) from activity_link")).isEqualTo(1);
    }

    @Test
    void 지운_활동과_경험의_수정_이력은_남고_deleted_다() throws Exception {
        long experienceId = jdbc.queryForObject("select min(id) from experience where activity_id = ?", Long.class, appId);
        mvc.perform(patch("/api/activities/{id}", appId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"바꾼 이름\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/experiences/{id}", experienceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"고친 경험\"}")).andExpect(status().isOk());

        mvc.perform(delete("/api/activities/{id}", appId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].target.deleted").value(true))
                .andExpect(jsonPath("$.revisions[1].target.deleted").value(true));
    }

    // 테스트 트랜잭션 밖에서 돌려 지우기 자체의 트랜잭션을 본다. 활동 DELETE 를 DB 트리거로 실패시킨다
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 중간에_실패하면_아무것도_바뀌지_않는다() throws Exception {
        long experiencesBefore = count("select count(*) from experience where activity_id = " + studyId);
        jdbc.execute("""
                create function fail_activity_delete() returns trigger language plpgsql as
                $$ begin raise exception 'test: activity delete blocked'; end $$""");
        jdbc.execute("""
                create trigger fail_activity_delete before delete on activity
                for each row execute function fail_activity_delete()""");
        try {
            mvc.perform(delete("/api/activities/{id}", studyId)).andExpect(status().isInternalServerError());

            assertThat(count("select count(*) from experience where activity_id = " + studyId)).isEqualTo(experiencesBefore);
            assertThat(count("select count(*) from activity where id = " + studyId)).isEqualTo(1L);
        } finally {
            jdbc.execute("drop trigger fail_activity_delete on activity");
            jdbc.execute("drop function fail_activity_delete()");
        }
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}

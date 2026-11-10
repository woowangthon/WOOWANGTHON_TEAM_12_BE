package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.mcp.McpWriteService;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionRepository;
import jakarta.persistence.EntityManager;
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

/**
 * ETC docs/contracts/revision-api.md — 수정 이력 저장 · 조회 · 되돌리기 (SPEC S-80 · ADR-013, BE#127).
 * 시드를 켜고 테스트 계정으로 돌린다. 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class RevisionApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    ActivityRepository activities;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    QuestionRepository questions;

    @Autowired
    McpConnectionService connections;

    @Autowired
    McpWriteService writes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    MockMvc web;
    long userId;
    long activityId;
    long experienceId;
    long questionId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        web = TestLogin.mvc(context, userId);
        Activity activity = activities.findTimeline(userId).getFirst();
        activityId = activity.getId();
        Experience experience = activity.getExperiences().getFirst();
        experienceId = experience.getId();
        questionId = question(userId);
    }

    private long question(long owner) {
        Application application = applications.save(new Application(owner, "OO 백엔드 부트캠프"));
        return questions.saveAndFlush(new Question(application, "기술적인 문제를 해결한 경험을 적어 주세요.")).getId();
    }

    // --- 남기기 ---

    @Test
    void 경험을_고치면_전_후_스냅샷이_남고_안_바뀐_저장은_남지_않는다() throws Exception {
        String before = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);

        patchExperience(experienceId, "{\"content\":\"고친 본문\"}").andExpect(status().isOk());
        // 같은 값을 다시 보내거나 스냅샷에 없는 칸만 고친 저장은 남기지 않는다
        patchExperience(experienceId, "{\"content\":\"고친 본문\"}").andExpect(status().isOk());
        web.perform(put("/api/experiences/{id}/saved", experienceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"saved\":true}")).andExpect(status().isOk());

        web.perform(get("/api/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.revisions[0].targetType").value("EXPERIENCE"))
                .andExpect(jsonPath("$.revisions[0].targetId").value(experienceId))
                .andExpect(jsonPath("$.revisions[0].kind").value("EDIT"))
                .andExpect(jsonPath("$.revisions[0].by").value("USER"))
                .andExpect(jsonPath("$.revisions[0].client").value(nullValue()))
                .andExpect(jsonPath("$.revisions[0].mcpChangeId").value(nullValue()))
                .andExpect(jsonPath("$.revisions[0].restoredFromId").value(nullValue()))
                .andExpect(jsonPath("$.revisions[0].createdAt", matchesPattern(".*\\+09:00")))
                .andExpect(jsonPath("$.revisions[0].target.deleted").value(false))
                .andExpect(jsonPath("$.revisions[0].target.activityId").value(activityId))
                .andExpect(jsonPath("$.revisions[0].target.activityName").isString())
                .andExpect(jsonPath("$.revisions[0].target.questionId").value(nullValue()))
                .andExpect(jsonPath("$.revisions[0].before.content").value(before))
                .andExpect(jsonPath("$.revisions[0].after.content").value("고친 본문"))
                .andExpect(jsonPath("$.revisions[0].after.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.revisions[0].after.actor").isString())
                .andExpect(jsonPath("$.revisions[0].after.competencies").isArray())
                .andExpect(jsonPath("$.revisions[0].after.competenciesBy").isString());
    }

    @Test
    void 활동은_이름_기간이_바뀐_때만_남고_계기만_고친_저장은_남지_않는다() throws Exception {
        patchActivity(activityId, "{\"note\":\"계기 한 줄\"}").andExpect(status().isOk());
        web.perform(get("/api/revisions")).andExpect(jsonPath("$.revisions.length()").value(0));

        patchActivity(activityId, "{\"name\":\"새 이름\",\"period\":{\"start\":\"2025-01\",\"end\":\"2025-06\",\"ongoing\":false}}")
                .andExpect(status().isOk());

        web.perform(get("/api/revisions"))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].targetType").value("ACTIVITY"))
                .andExpect(jsonPath("$.revisions[0].targetId").value(activityId))
                .andExpect(jsonPath("$.revisions[0].kind").value("EDIT"))
                .andExpect(jsonPath("$.revisions[0].before.name").isString())
                .andExpect(jsonPath("$.revisions[0].after.name").value("새 이름"))
                .andExpect(jsonPath("$.revisions[0].after.period.start").value("2025-01"))
                .andExpect(jsonPath("$.revisions[0].after.period.end").value("2025-06"))
                .andExpect(jsonPath("$.revisions[0].after.period.ongoing").value(false))
                .andExpect(jsonPath("$.revisions[0].target.activityId").value(nullValue()));
    }

    @Test
    void 활동의_주체_한_번에_정하기는_바뀐_경험마다_한_건() throws Exception {
        jdbc.update("update experience set actor = 'UNKNOWN' where activity_id = ?", activityId);
        long unknown = jdbc.queryForObject("select count(*) from experience where activity_id = ?", Long.class, activityId);
        em.clear();

        web.perform(post("/api/activities/{id}/actor", activityId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":\"ME\"}")).andExpect(status().isOk());

        web.perform(get("/api/revisions?limit=50"))
                .andExpect(jsonPath("$.revisions.length()").value((int) unknown))
                .andExpect(jsonPath("$.revisions[0].before.actor").value("UNKNOWN"))
                .andExpect(jsonPath("$.revisions[0].after.actor").value("ME"));
    }

    @Test
    void 답은_다_썼어요와_초안으로_덮어쓰기_때만_남고_자동_저장은_남지_않는다() throws Exception {
        // 자동 저장(fromDraft 없음 · false)은 남기지 않는다
        saveDraft("{\"text\":\"쓰는 중\"}");
        saveDraft("{\"text\":\"쓰는 중 2\",\"fromDraft\":false}");
        web.perform(get("/api/revisions")).andExpect(jsonPath("$.revisions.length()").value(0));

        // 초안을 답칸에 넣은 저장 — before 는 그때 답칸에 보이던 글(임시 글)
        saveDraft("{\"text\":\"초안에서 넣은 글\",\"fromDraft\":true}");
        // 같은 글을 또 넣으면 바뀐 게 없어 남지 않는다
        saveDraft("{\"text\":\"초안에서 넣은 글\",\"fromDraft\":true}");
        // 처음 확정 — before 는 ""
        confirm("다 쓴 답");
        // 같은 글로 다시 확정 — 아무것도 바꾸지 않아 남지 않는다
        confirm("다 쓴 답");
        confirm("고친 답");

        web.perform(get("/api/revisions?targetType=ANSWER&targetId={id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(3))
                .andExpect(jsonPath("$.revisions[0].kind").value("CONFIRM"))
                .andExpect(jsonPath("$.revisions[0].before.text").value("다 쓴 답"))
                .andExpect(jsonPath("$.revisions[0].after.text").value("고친 답"))
                .andExpect(jsonPath("$.revisions[0].target.questionId").value(questionId))
                .andExpect(jsonPath("$.revisions[0].target.questionText").value("기술적인 문제를 해결한 경험을 적어 주세요."))
                .andExpect(jsonPath("$.revisions[0].target.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.revisions[0].target.applicationId").isNumber())
                .andExpect(jsonPath("$.revisions[0].target.activityId").value(nullValue()))
                .andExpect(jsonPath("$.revisions[1].kind").value("CONFIRM"))
                .andExpect(jsonPath("$.revisions[1].before.text").value(""))
                .andExpect(jsonPath("$.revisions[1].after.text").value("다 쓴 답"))
                .andExpect(jsonPath("$.revisions[2].kind").value("DRAFT_OVERWRITE"))
                .andExpect(jsonPath("$.revisions[2].before.text").value("쓰는 중 2"))
                .andExpect(jsonPath("$.revisions[2].after.text").value("초안에서 넣은 글"));
    }

    @Test
    void 외부_AI_고치기도_같은_목록에_들고_웹_되돌리기_맞아요는_사용자_기록() throws Exception {
        connections.create(userId);
        String content = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);
        long change = writes.updateExperience(userId, "claude-ai", experienceId, content, "외부 AI 가 고친 본문", "이유")
                .changeId();

        web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId={id}", experienceId))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].kind").value("EDIT"))
                .andExpect(jsonPath("$.revisions[0].by").value("EXTERNAL_AI"))
                .andExpect(jsonPath("$.revisions[0].client").value("claude-ai"))
                .andExpect(jsonPath("$.revisions[0].mcpChangeId").value(change))
                .andExpect(jsonPath("$.revisions[0].before.content").value(content))
                .andExpect(jsonPath("$.revisions[0].after.content").value("외부 AI 가 고친 본문"))
                .andExpect(jsonPath("$.revisions[0].after.sourceStatus").value("NEEDS_REVIEW"));

        // 변경 기록에서 맞아요 — 출처 상태가 바뀐 기록
        web.perform(post("/api/mcp/changes/{id}/confirm", change)).andExpect(status().isOk());
        web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId={id}", experienceId))
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].by").value("USER"))
                .andExpect(jsonPath("$.revisions[0].mcpChangeId").value(change))
                .andExpect(jsonPath("$.revisions[0].before.sourceStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.revisions[0].after.sourceStatus").value("USER_ENTERED"));
    }

    @Test
    void 변경_기록에서_되돌린_것도_사용자_기록으로_남는다() throws Exception {
        connections.create(userId);
        String content = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);
        long change = writes.updateExperience(userId, "claude-ai", experienceId, content, "외부 AI 가 고친 본문", "이유")
                .changeId();

        web.perform(post("/api/mcp/changes/{id}/undo", change)).andExpect(status().isOk());

        web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId={id}", experienceId))
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].by").value("USER"))
                .andExpect(jsonPath("$.revisions[0].mcpChangeId").value(change))
                .andExpect(jsonPath("$.revisions[0].after.content").value(content));
    }

    // --- 보기 ---

    @Test
    void 새것_먼저_커서로_넘기고_항목만_볼_수_있다() throws Exception {
        patchExperience(experienceId, "{\"content\":\"첫 번째\"}");
        patchActivity(activityId, "{\"name\":\"두 번째\"}");
        patchExperience(experienceId, "{\"content\":\"세 번째\"}");

        String first = web.perform(get("/api/revisions?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].after.content").value("세 번째"))
                .andExpect(jsonPath("$.revisions[1].after.name").value("두 번째"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");

        web.perform(get("/api/revisions").param("limit", "2").param("cursor", cursor))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].after.content").value("첫 번째"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        // 그 항목만
        web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId={id}", experienceId))
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[*].targetType", not(hasItem("ACTIVITY"))));
        web.perform(get("/api/revisions?targetType=ACTIVITY&targetId={id}", activityId))
                .andExpect(jsonPath("$.revisions.length()").value(1));
        // 이력이 없는 항목은 빈 목록
        web.perform(get("/api/revisions?targetType=ANSWER&targetId={id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void 잘못된_요청은_400() throws Exception {
        expectError(web.perform(get("/api/revisions?targetType=EXPERIENCE")), 400, "INVALID_REQUEST");
        expectError(web.perform(get("/api/revisions?targetId=1")), 400, "INVALID_REQUEST");
        expectError(web.perform(get("/api/revisions?targetType=NOPE&targetId=1")), 400, "INVALID_REQUEST");
        expectError(web.perform(get("/api/revisions?limit=0")), 400, "INVALID_REQUEST");
        expectError(web.perform(get("/api/revisions?limit=51")), 400, "INVALID_REQUEST");
        expectError(web.perform(get("/api/revisions?cursor=not-a-cursor")), 400, "INVALID_REQUEST");
        web.perform(get("/api/revisions?limit=50")).andExpect(status().isOk());
    }

    @Test
    void 로그인해야_본다() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
        anonymous.perform(get("/api/revisions")).andExpect(status().isUnauthorized());
        anonymous.perform(post("/api/revisions/1/restore")).andExpect(status().isUnauthorized());
    }

    @Test
    void 남의_항목과_이력은_없는_것과_같다() throws Exception {
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        Activity theirs = activities.saveAndFlush(new Activity(otherId, "남의 활동", new Period(null, null, false)));
        long theirQuestion = question(otherId);
        MockMvc other = TestLogin.mvc(context, otherId);
        other.perform(patch("/api/activities/{id}", theirs.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"남이 고친 이름\"}")).andExpect(status().isOk());
        long theirRevision = jdbc.queryForObject("select id from revision where user_id = ?", Long.class, otherId);

        // 내 목록에는 안 보이고, 항목만 볼 때도 404
        web.perform(get("/api/revisions")).andExpect(jsonPath("$.revisions.length()").value(0));
        expectError(web.perform(get("/api/revisions?targetType=ACTIVITY&targetId={id}", theirs.getId())), 404,
                "ACTIVITY_NOT_FOUND");
        expectError(web.perform(get("/api/revisions?targetType=ANSWER&targetId={id}", theirQuestion)), 404,
                "QUESTION_NOT_FOUND");
        expectError(web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId=999999")), 404,
                "EXPERIENCE_NOT_FOUND");
        // 남의 기록을 되돌릴 수 없고, 활동은 그대로다
        expectError(web.perform(post("/api/revisions/{id}/restore", theirRevision)), 404, "REVISION_NOT_FOUND");
        expectError(web.perform(post("/api/revisions/999999/restore")), 404, "REVISION_NOT_FOUND");
        assertThat(jdbc.queryForObject("select name from activity where id = ?", String.class, theirs.getId()))
                .isEqualTo("남이 고친 이름");
    }

    @Test
    void 지운_경험의_이력은_최근_수정에_deleted_로_남고_되돌릴_수_없다() throws Exception {
        patchExperience(experienceId, "{\"content\":\"지우기 전 고침\"}");
        long revision = jdbc.queryForObject("select id from revision where user_id = ?", Long.class, userId);

        web.perform(delete("/api/experiences/{id}", experienceId)).andExpect(status().isNoContent());

        web.perform(get("/api/revisions"))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].target.deleted").value(true))
                .andExpect(jsonPath("$.revisions[0].target.activityName").isString());
        expectError(web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId={id}", experienceId)), 404,
                "EXPERIENCE_NOT_FOUND");
        expectError(web.perform(post("/api/revisions/{id}/restore", revision)), 409, "REVISION_TARGET_DELETED");
    }

    // --- 되돌리기 ---

    @Test
    void 경험을_되돌리면_그_수정_전_값과_출처_상태로_돌아가고_RESTORE_가_더해진다() throws Exception {
        String content = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);
        String statusBefore = jdbc.queryForObject("select source_status from experience where id = ?", String.class,
                experienceId);
        patchExperience(experienceId, "{\"content\":\"고친 본문\"}");
        patchExperience(experienceId, "{\"content\":\"또 고친 본문\"}");
        long first = revisionIdAfter("고친 본문");

        // 첫 수정 전 = 처음 값. 그 사이 한 번 더 고쳐졌어도 쓴다
        ResultActions restored = web.perform(post("/api/revisions/{id}/restore", first));
        restored.andExpect(status().isOk())
                .andExpect(jsonPath("$.revision.kind").value("RESTORE"))
                .andExpect(jsonPath("$.revision.by").value("USER"))
                .andExpect(jsonPath("$.revision.restoredFromId").value(first))
                .andExpect(jsonPath("$.revision.before.content").value("또 고친 본문"))
                .andExpect(jsonPath("$.revision.after.content").value(content))
                .andExpect(jsonPath("$.experience.id").value(experienceId))
                .andExpect(jsonPath("$.experience.content").value(content))
                .andExpect(jsonPath("$.experience.sourceStatus").value(statusBefore))
                .andExpect(jsonPath("$.activity").value(nullValue()))
                .andExpect(jsonPath("$.answerDraft").value(nullValue()));
        assertThat(jdbc.queryForObject("select source_status from experience where id = ?", String.class, experienceId))
                .isEqualTo(statusBefore);

        // 지난 기록은 그대로고 RESTORE 가 하나 더 생겼다
        web.perform(get("/api/revisions?targetType=EXPERIENCE&targetId={id}", experienceId))
                .andExpect(jsonPath("$.revisions.length()").value(3))
                .andExpect(jsonPath("$.revisions[0].kind").value("RESTORE"))
                .andExpect(jsonPath("$.revisions[2].after.content").value("고친 본문"));

        // 두 번 눌러도 한 번만 — 지금 값이 이미 그 수정 전 값이다
        expectError(web.perform(post("/api/revisions/{id}/restore", first)), 409, "NOTHING_TO_RESTORE");
        web.perform(get("/api/revisions")).andExpect(jsonPath("$.revisions.length()").value(3));
    }

    @Test
    void 경험을_되돌려도_근거와_활동_소속은_그대로고_외부_AI_변경은_더는_되돌릴_수_없다() throws Exception {
        connections.create(userId);
        String content = jdbc.queryForObject("select content from experience where id = ?", String.class, experienceId);
        long change = writes.updateExperience(userId, "claude-ai", experienceId, content, "외부 AI 가 고친 본문", "이유")
                .changeId();
        long citations = jdbc.queryForObject("select count(*) from citation where experience_id = ?", Long.class,
                experienceId);
        long revision = jdbc.queryForObject("select id from revision where user_id = ?", Long.class, userId);

        web.perform(post("/api/revisions/{id}/restore", revision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experience.content").value(content))
                .andExpect(jsonPath("$.experience.activityId").value(activityId))
                .andExpect(jsonPath("$.experience.citations.length()").value((int) citations))
                .andExpect(jsonPath("$.experience.mcpChange").value(nullValue()));

        // 사용자가 손댄 것이므로 변경 기록에서는 더 되돌릴 수 없다
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[0].id").value(change))
                .andExpect(jsonPath("$.changes[0].undoable").value(false))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value("CHANGED_AFTER"));
    }

    @Test
    void 활동을_되돌리면_이름_기간만_돌아간다() throws Exception {
        String name = jdbc.queryForObject("select name from activity where id = ?", String.class, activityId);
        patchActivity(activityId, "{\"name\":\"새 이름\",\"note\":\"계기\"}");
        long revision = jdbc.queryForObject("select id from revision where user_id = ?", Long.class, userId);

        web.perform(post("/api/revisions/{id}/restore", revision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision.kind").value("RESTORE"))
                .andExpect(jsonPath("$.revision.targetType").value("ACTIVITY"))
                .andExpect(jsonPath("$.activity.id").value(activityId))
                .andExpect(jsonPath("$.activity.name").value(name))
                .andExpect(jsonPath("$.activity.period").exists())
                .andExpect(jsonPath("$.experience").value(nullValue()));
        // 계기는 스냅샷에 없어 그대로다
        assertThat(jdbc.queryForObject("select note from activity where id = ?", String.class, activityId))
                .isEqualTo("계기");
        expectError(web.perform(post("/api/revisions/{id}/restore", revision)), 409, "NOTHING_TO_RESTORE");
    }

    @Test
    void 답을_되돌리면_확정_답은_그대로고_임시_글로_들어온다() throws Exception {
        confirm("처음 답");
        confirm("고친 답");
        long toFirst = jdbc.queryForObject(
                "select id from revision where target_type = 'ANSWER' and after_snapshot like '%고친 답%'", Long.class);
        long usage = jdbc.queryForObject("select count(*) from analysis_usage", Long.class);

        web.perform(post("/api/revisions/{id}/restore", toFirst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision.kind").value("RESTORE"))
                .andExpect(jsonPath("$.revision.before.text").value("고친 답"))
                .andExpect(jsonPath("$.revision.after.text").value("처음 답"))
                .andExpect(jsonPath("$.answerDraft.text").value("처음 답"))
                .andExpect(jsonPath("$.answerDraft.state").value(nullValue()))
                .andExpect(jsonPath("$.experience").value(nullValue()))
                .andExpect(jsonPath("$.activity").value(nullValue()));

        web.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.answer.text").value("고친 답"))
                .andExpect(jsonPath("$.answerDraft.text").value("처음 답"));
        // AI 를 부르지 않고 분석 횟수를 쓰지 않는다
        assertThat(jdbc.queryForObject("select count(*) from analysis_usage", Long.class)).isEqualTo(usage);
        expectError(web.perform(post("/api/revisions/{id}/restore", toFirst)), 409, "NOTHING_TO_RESTORE");

        // 「다 썼어요」를 눌러야 확정이고, 그때 다시 CONFIRM 이 남는다
        confirm("처음 답");
        web.perform(get("/api/revisions?targetType=ANSWER&targetId={id}", questionId))
                .andExpect(jsonPath("$.revisions[0].kind").value("CONFIRM"))
                .andExpect(jsonPath("$.revisions[1].kind").value("RESTORE"));
    }

    @Test
    void 처음_확정_전으로_되돌리면_빈_임시_글이_들어오고_다시_누르면_할_일이_없다() throws Exception {
        confirm("처음 답");
        long first = jdbc.queryForObject("select id from revision where target_type = 'ANSWER'", Long.class);

        // 답칸에는 확정 답이 보인다 — 빈 글로 되돌릴 수 있다
        web.perform(post("/api/revisions/{id}/restore", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerDraft.text").value(""));
        expectError(web.perform(post("/api/revisions/{id}/restore", first)), 409, "NOTHING_TO_RESTORE");
    }

    // --- 외부 AI 에는 열지 않는다 ---

    @Test
    void MCP_에_이력_도구가_없다() throws Exception {
        var created = connections.create(userId);
        connections.setWriteEnabled(userId, true);
        String token = created.url().substring(created.url().lastIndexOf('/') + 1);
        MockMvc client = MockMvcBuilders.webAppContextSetup(context).build();

        client.perform(post("/mcp/{token}", token).contentType(MediaType.APPLICATION_JSON)
                .accept("application/json, text/event-stream")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("list_revisions"))))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("restore_revision"))));
    }

    // --- 도우미 ---

    private ResultActions patchExperience(long id, String body) throws Exception {
        return web.perform(patch("/api/experiences/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchActivity(long id, String body) throws Exception {
        return web.perform(patch("/api/activities/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void saveDraft(String body) throws Exception {
        web.perform(put("/api/questions/{id}/answer-draft", questionId).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
    }

    private void confirm(String text) throws Exception {
        web.perform(put("/api/questions/{id}/answer", questionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    // 본문이 content 로 바뀐 수정 — 되돌리면 그 수정 전 값이 된다
    private long revisionIdAfter(String content) {
        return jdbc.queryForObject("select id from revision where target_type = 'EXPERIENCE' and target_id = ? "
                + "and after_snapshot like ?", Long.class, experienceId, "%\"" + content + "\"%");
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}

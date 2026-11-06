package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.ai.ExtractResponse;
import com.woowangthon.backend.job.AnalysisService;
import com.woowangthon.backend.job.ExtractionRunner;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.mcp.McpWriteService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/mcp-api.md 「변경 기록」 — 보기 · 되돌리기 · 맞아요(S-53 · S-54, BE#92).
 * 시드를 켜고 테스트 계정으로 돌린다. 분석은 AI 응답을 바로 저장하고, 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class McpChangeApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    McpConnectionService connections;

    @Autowired
    McpWriteService writes;

    @Autowired
    AnalysisService analyses;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @MockitoBean
    ExtractionRunner extractions;

    MockMvc web;
    long userId;

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        web = TestLogin.mvc(context, userId);
    }

    // --- 보기 ---

    @Test
    void 변경이_없으면_빈_목록이고_로그인해야_본다() throws Exception {
        web.perform(get("/api/mcp/changes")).andExpect(status().isOk()).andExpect(jsonPath("$.changes.length()").value(0));

        MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/mcp/changes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 새것_먼저_계약_모양으로_보이고_연결을_끊어도_남는다() throws Exception {
        connections.create(userId);
        Added added = addCompletedSource(3);
        Map<String, Object> e = confirmedExperience();
        long experienceId = (Long) e.get("id");
        long rewrite = rewrite(experienceId, (String) e.get("content"), "새 본문", "이유");
        web.perform(delete("/api/mcp/connection")).andExpect(status().isNoContent());

        web.perform(get("/api/mcp/changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.changes[0].id").value(rewrite))
                .andExpect(jsonPath("$.changes[0].type").value("UPDATE_EXPERIENCE"))
                .andExpect(jsonPath("$.changes[0].client").value("claude-ai"))
                .andExpect(jsonPath("$.changes[0].reason").value("이유"))
                .andExpect(jsonPath("$.changes[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.changes[0].createdAt", matchesPattern(".*\\+09:00")))
                .andExpect(jsonPath("$.changes[0].decidedAt").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].undoable").value(true))
                .andExpect(jsonPath("$.changes[0].undoBlockedReason").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].experience.id").value(experienceId))
                .andExpect(jsonPath("$.changes[0].experience.activityId").value(e.get("activity_id")))
                .andExpect(jsonPath("$.changes[0].experience.activityName").isString())
                .andExpect(jsonPath("$.changes[0].experience.beforeContent").value(e.get("content")))
                .andExpect(jsonPath("$.changes[0].experience.afterContent").value("새 본문"))
                .andExpect(jsonPath("$.changes[0].experience.beforeSourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.changes[0].source").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].application").value(nullValue()))
                .andExpect(jsonPath("$.changes[0].supplement").value(nullValue()))
                .andExpect(jsonPath("$.changes[1].id").value(added.changeId()))
                .andExpect(jsonPath("$.changes[1].type").value("ADD_SOURCE"))
                .andExpect(jsonPath("$.changes[1].reason").value(nullValue()))
                .andExpect(jsonPath("$.changes[1].source.id").value(added.sourceId()))
                .andExpect(jsonPath("$.changes[1].source.name", matchesPattern("claude-ai 에서 보냄 — .*")))
                .andExpect(jsonPath("$.changes[1].source.jobId").value(added.jobId()))
                .andExpect(jsonPath("$.changes[1].source.jobStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.changes[1].source.experienceCount").value(3))
                .andExpect(jsonPath("$.changes[1].experience").value(nullValue()));
    }

    @Test
    void 최근_100건만_보이고_남의_변경은_보이지_않는다() throws Exception {
        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        insertChanges(userId, 101);
        insertChanges(otherId, 1);

        web.perform(get("/api/mcp/changes")).andExpect(jsonPath("$.changes.length()").value(100));
        TestLogin.mvc(context, otherId).perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes.length()").value(1));
    }

    @Test
    void 분석_중이면_ANALYZING_실패면_ANALYSIS_FAILED_로_막히고_개수는_0() throws Exception {
        long analyzing = writes.addSource(userId, "claude-ai", "글").changeId();
        expectBlocked(0, "ANALYZING");
        web.perform(get("/api/mcp/changes")).andExpect(jsonPath("$.changes[0].source.experienceCount").value(0));
        undo(analyzing).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));

        jdbc.update("update analysis_job set status = 'FAILED' where user_id = ?", userId);
        settle();
        expectBlocked(0, "ANALYSIS_FAILED");
        undo(analyzing).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));
    }

    // --- 경험 고치기 되돌리기 ---

    @Test
    void 경험_고치기를_되돌리면_본문_출처_상태만_돌아가고_mcpChange_가_빈다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        List<Map<String, Object>> others = untouchedColumns(id);
        long change = rewrite(id, (String) e.get("content"), "새 본문", "이유");

        undo(change).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("UNDONE"))
                .andExpect(jsonPath("$.change.decidedAt", matchesPattern(".*\\+09:00")))
                .andExpect(jsonPath("$.change.undoable").value(false))
                .andExpect(jsonPath("$.change.undoBlockedReason").value("DECIDED"))
                .andExpect(jsonPath("$.removedExperiences").value(0))
                .andExpect(jsonPath("$.keptExperiences").value(0));

        settle();
        assertThat(experienceRow(id)).containsEntry("content", e.get("content"))
                .containsEntry("source_status", "SOURCE_CONFIRMED")
                .containsEntry("mcp_change_id", null).containsEntry("mcp_client", null);
        assertThat(untouchedColumns(id)).isEqualTo(others);
    }

    @Test
    void 두_번_고친_경험의_뒤_변경을_무르면_앞_변경이_다시_정할_것으로_돌아온다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        long first = rewrite(id, (String) e.get("content"), "첫째", "이유");
        long second = rewrite(id, "첫째", "둘째", "이유");
        expectBlocked(1, "CHANGED_AFTER");

        undo(second).andExpect(status().isOk());

        settle();
        assertThat(experienceRow(id)).containsEntry("content", "첫째").containsEntry("source_status", "NEEDS_REVIEW")
                .containsEntry("mcp_change_id", first).containsEntry("mcp_client", "claude-ai");
        expectUndoable(1);

        undo(first).andExpect(status().isOk());
        settle();
        assertThat(experienceRow(id)).containsEntry("content", e.get("content"))
                .containsEntry("source_status", "SOURCE_CONFIRMED").containsEntry("mcp_change_id", null);
    }

    @Test
    void 웹에서_고쳤거나_지운_경험은_CHANGED_AFTER_로_막힌다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        long change = rewrite(id, (String) e.get("content"), "새 본문", "이유");
        web.perform(patch("/api/experiences/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\": \"TEAM\"}")).andExpect(status().isOk());
        settle();

        expectBlocked(0, "CHANGED_AFTER"); // 본문은 같지만 NEEDS_REVIEW 가 아니다
        undo(change).andExpect(status().isConflict());
        confirm(change).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_CONFIRMABLE"));

        web.perform(delete("/api/experiences/{id}", id)).andExpect(status().isNoContent());
        settle();
        expectBlocked(0, "CHANGED_AFTER");
        web.perform(get("/api/mcp/changes")).andExpect(jsonPath("$.changes[0].experience.afterContent").value("새 본문"));
    }

    // --- 자료 넣기 되돌리기 ---

    @Test
    void 자료_넣기를_되돌리면_경험과_빈_활동을_지우고_원본은_남긴다() throws Exception {
        Added added = addCompletedSource(3);
        long waiting = jdbc.queryForObject("select id from activity where user_id = ? and id <> ? order by id limit 1",
                Long.class, userId, added.activityId());
        jdbc.update("update activity set merge_suggestion_activity_id = ? where id = ?", added.activityId(), waiting);
        settle();

        undo(added.changeId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("UNDONE"))
                .andExpect(jsonPath("$.change.source.experienceCount").value(0))
                .andExpect(jsonPath("$.removedExperiences").value(3))
                .andExpect(jsonPath("$.keptExperiences").value(0));

        settle();
        assertThat(count("experience where id = any(?)", added.experienceIdArray())).isZero();
        assertThat(count("citation where source_id = ?", added.sourceId())).isZero();
        assertThat(count("activity where id = ?", added.activityId())).isZero();
        assertThat(count("source where id = ?", added.sourceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select merge_suggestion_activity_id from activity where id = ?", Long.class, waiting))
                .isNull();
        web.perform(get("/api/mcp/changes")).andExpect(jsonPath("$.changes[0].source.jobStatus").value("COMPLETED"));
    }

    @Test
    void 사용자가_손댄_경험은_남기고_활동도_남는다() throws Exception {
        Added added = addCompletedSource(5);
        List<Long> ids = added.experienceIds();
        // 1 내가 입력 · 2 보완 · 3 다른 활동으로 옮김 · 4 외부 AI 변경 기록 · 5 그대로
        web.perform(patch("/api/experiences/{id}", ids.get(0)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"내가 고침\"}")).andExpect(status().isOk());
        jdbc.update("insert into supplement (experience_id, question, answer) values (?, '질문', '답')", ids.get(1));
        long elsewhere = jdbc.queryForObject("select id from activity where user_id = ? and id <> ? order by id limit 1",
                Long.class, userId, added.activityId());
        web.perform(post("/api/experiences/{id}/move", ids.get(2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toActivityId\": " + elsewhere + "}")).andExpect(status().isOk());
        settle();
        rewrite(ids.get(3), "경험 3", "외부 AI 가 고침", "이유");

        undo(added.changeId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.removedExperiences").value(1))
                .andExpect(jsonPath("$.keptExperiences").value(4))
                .andExpect(jsonPath("$.change.source.experienceCount").value(4));

        settle();
        assertThat(count("experience where id = ?", ids.get(4))).isZero();
        assertThat(count("experience where id = any(?)", added.experienceIdArray())).isEqualTo(4);
        assertThat(count("citation where source_id = ?", added.sourceId())).isEqualTo(4);
        assertThat(count("activity where id = ?", added.activityId())).isEqualTo(1);
    }

    @Test
    void 합치기로_만든_활동이_사라졌으면_그_경험은_옮겨진_것으로_보고_남긴다() throws Exception {
        Added added = addCompletedSource(2);
        long keep = jdbc.queryForObject("select id from activity where user_id = ? and id <> ? order by id limit 1",
                Long.class, userId, added.activityId());
        web.perform(post("/api/activities/{id}/merge", keep).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromActivityId\": " + added.activityId() + "}")).andExpect(status().isOk());
        settle();

        undo(added.changeId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.removedExperiences").value(0))
                .andExpect(jsonPath("$.keptExperiences").value(2));

        settle();
        assertThat(count("experience where id = any(?)", added.experienceIdArray())).isEqualTo(2);
    }

    @Test
    void 경험이_빠져도_계기나_이어짐이_있는_활동은_남긴다() throws Exception {
        Added withNote = addCompletedSource(1);
        jdbc.update("update activity set note = '계기' where id = ?", withNote.activityId());
        Added withLink = addCompletedSource(1);
        long other = jdbc.queryForObject("select id from activity where user_id = ? and id not in (?, ?) order by id limit 1",
                Long.class, userId, withNote.activityId(), withLink.activityId());
        jdbc.update("insert into activity_link (from_activity_id, to_activity_id, reason, status) values (?, ?, '이유', 'SUGGESTED')",
                other, withLink.activityId());
        settle();

        undo(withNote.changeId()).andExpect(status().isOk()).andExpect(jsonPath("$.removedExperiences").value(1));
        undo(withLink.changeId()).andExpect(status().isOk()).andExpect(jsonPath("$.removedExperiences").value(1));

        settle();
        assertThat(count("activity where id = ?", withNote.activityId())).isEqualTo(1);
        assertThat(count("activity where id = ?", withLink.activityId())).isEqualTo(1);
    }

    // --- 맞아요 ---

    @Test
    void 맞아요면_내가_입력이_되고_역량_출처는_그대로이고_되돌릴_수_없다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        long change = rewrite(id, (String) e.get("content"), "새 본문", "이유");

        confirm(change).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.change.decidedAt", matchesPattern(".*\\+09:00")))
                .andExpect(jsonPath("$.change.undoable").value(false))
                .andExpect(jsonPath("$.change.undoBlockedReason").value("DECIDED"));

        settle();
        assertThat(experienceRow(id)).containsEntry("content", "새 본문").containsEntry("source_status", "USER_ENTERED")
                .containsEntry("mcp_change_id", null).containsEntry("mcp_client", null)
                .containsEntry("competencies_by", "AI");
        undo(change).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));
        confirm(change).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_CONFIRMABLE"));
    }

    @Test
    void 자료_넣기에는_맞아요가_없다() throws Exception {
        Added added = addCompletedSource(1);

        confirm(added.changeId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_CONFIRMABLE"));
    }

    // --- 주인 · 두 번 ---

    @Test
    void 없거나_남의_변경은_404_이고_그대로다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        long change = rewrite(id, (String) e.get("content"), "새 본문", "이유");
        MockMvc other = TestLogin.mvc(context, users.save(new AppUser("other", "hash", null)).getId());

        for (long target : List.of(change, 999999L)) {
            other.perform(post("/api/mcp/changes/{id}/undo", target)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_FOUND"));
            other.perform(post("/api/mcp/changes/{id}/confirm", target)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_FOUND"));
        }

        settle();
        assertThat(experienceRow(id)).containsEntry("content", "새 본문").containsEntry("mcp_change_id", change);
    }

    @Test
    void 두_번_누르면_두_번째는_409() throws Exception {
        Added added = addCompletedSource(2);

        undo(added.changeId()).andExpect(status().isOk());
        settle();
        undo(added.changeId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MCP_CHANGE_NOT_UNDOABLE"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시에_눌러도_한_번만_일어난다() throws Exception {
        Map<String, Object> e = confirmedExperience();
        long id = (Long) e.get("id");
        Map<String, Object> before = experienceRow(id);
        long change = rewrite(id, (String) e.get("content"), "새 본문", "이유");
        Callable<Integer> press = () -> undo(change).andReturn().getResponse().getStatus();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(press, press));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdown();
            jdbc.update("update experience set content = ?, source_status = ?, mcp_change_id = null, mcp_client = null where id = ?",
                    before.get("content"), before.get("source_status"), id);
            jdbc.update("delete from mcp_change where user_id = ?", userId);
        }
    }

    // --- 도우미 ---

    private record Added(long changeId, long sourceId, long jobId, long activityId, List<Long> experienceIds) {
        Long[] experienceIdArray() {
            return experienceIds.toArray(Long[]::new);
        }
    }

    // 외부 AI 가 넣은 자료의 분석을 끝낸다 — 경험 내용은 「경험 0」 · 「경험 1」 …
    private Added addCompletedSource(int experiences) {
        McpWriteService.AddedSource added = writes.addSource(userId, "claude-ai", "대화에서 나온 글");
        long jobId = added.submitted().jobId();
        List<ExtractResponse.ExperienceResult> extracted = new ArrayList<>();
        for (int i = 0; i < experiences; i++) {
            extracted.add(new ExtractResponse.ExperienceResult("경험 " + i, List.of(), "ME", "SOURCE_CONFIRMED",
                    List.of(new ExtractResponse.CitationResult("글", true, null))));
        }
        long activityId = analyses.complete(jobId, analyses.extractRequest(jobId), new ExtractResponse(
                List.of(new ExtractResponse.ActivityResult("넣은 활동", null, null, null, extracted)), "test-model")).getFirst();
        settle();
        List<Long> ids = jdbc.queryForList("select id from experience where activity_id = ? order by position", Long.class, activityId);
        return new Added(added.changeId(), added.submitted().sourceId(), jobId, activityId, ids);
    }

    private long rewrite(long experienceId, String expected, String content, String reason) {
        long change = writes.updateExperience(userId, "claude-ai", experienceId, expected, content, reason).changeId();
        settle();
        return change;
    }

    private ResultActions undo(long changeId) throws Exception {
        return web.perform(post("/api/mcp/changes/{id}/undo", changeId));
    }

    private ResultActions confirm(long changeId) throws Exception {
        return web.perform(post("/api/mcp/changes/{id}/confirm", changeId));
    }

    private void expectBlocked(int index, String reason) throws Exception {
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[" + index + "].undoable").value(false))
                .andExpect(jsonPath("$.changes[" + index + "].undoBlockedReason").value(reason));
    }

    private void expectUndoable(int index) throws Exception {
        web.perform(get("/api/mcp/changes"))
                .andExpect(jsonPath("$.changes[" + index + "].undoable").value(true))
                .andExpect(jsonPath("$.changes[" + index + "].undoBlockedReason").value(nullValue()));
    }

    // 한 테스트가 한 트랜잭션이라 요청마다 새 세션인 실제 상황에 맞춘다
    private void settle() {
        if (em.isJoinedToTransaction()) {
            em.flush();
            em.clear();
        }
    }

    private Map<String, Object> confirmedExperience() {
        return jdbc.queryForMap("""
                select e.id, e.activity_id, e.content from experience e
                join activity a on a.id = e.activity_id
                where a.user_id = ? and e.source_status = 'SOURCE_CONFIRMED' and e.competencies_by = 'AI'
                  and exists (select 1 from citation c where c.experience_id = e.id)
                order by (select count(*) from supplement s where s.experience_id = e.id) desc, e.id
                limit 1
                """, userId);
    }

    private Map<String, Object> experienceRow(long id) {
        return jdbc.queryForMap("select content, source_status, mcp_change_id, mcp_client, competencies_by from experience where id = ?", id);
    }

    private List<Map<String, Object>> untouchedColumns(long id) {
        return jdbc.queryForList("""
                select e.competencies::text, e.competencies_by, e.actor, e.position,
                       (select string_agg(c.id || ':' || c.quote, '|' order by c.id) from citation c where c.experience_id = e.id) citations,
                       (select string_agg(s.id || ':' || s.answer, '|' order by s.id) from supplement s where s.experience_id = e.id) supplements
                from experience e where e.id = ?
                """, id);
    }

    private void insertChanges(long owner, int count) {
        jdbc.update("insert into mcp_change (user_id, type, client, status) "
                + "select ?, 'UPDATE_EXPERIENCE', 'claude', 'APPLIED' from generate_series(1, ?)", owner, count);
    }

    private long count(String tableAndWhere, Object argument) {
        return jdbc.queryForObject("select count(*) from " + tableAndWhere, Long.class, argument);
    }
}

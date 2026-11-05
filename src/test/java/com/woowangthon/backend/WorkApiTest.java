package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.Question;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.WebApplicationContext;

/** ETC docs/contracts/work-api.md(S-47). 일은 표에 바로 넣는다 — 끝난 시각을 마음대로 정해야 해서. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WorkApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    long userId;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        cleanTables();
        userId = newUser();
        mvc = TestLogin.mvc(context, userId);
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("delete from chagok_run");
        jdbc.update("delete from analysis_job_activity");
        jdbc.update("delete from analysis_job");
        jdbc.update("delete from question");
        jdbc.update("delete from application");
        jdbc.update("delete from source");
        jdbc.update("delete from app_user where login_id like 'work-%'");
    }

    @Test
    void 일이_없으면_빈_배열이고_seenAt_은_null() throws Exception {
        mvc.perform(get("/api/work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seenAt", nullValue()))
                .andExpect(jsonPath("$.inProgress", hasSize(0)))
                .andExpect(jsonPath("$.recent", hasSize(0)));
    }

    @Test
    void 다섯_종류의_진행_중인_일을_시작이_최근인_것부터() throws Exception {
        long source = source(userId, "바꿔요-회고.md");
        long job = job(userId, source, "ANALYZING", null, "5 hours");
        long application = application(userId, "OO 부트캠프");
        long matching = question(userId, application, "지원 동기", "ANALYZING", "4 hours", null);
        long done = question(userId, application, "협업 경험", "COMPLETED", "1 day", "20 hours");
        long check = grounding(done, "ANALYZING", null, "3 hours", "3 hours");
        long draft = draft(done, "ANALYZING", null, "2 hours", "2 hours");
        long run = run(userId, "지원 만들어 줘", done, "RUNNING", "1 hour", null);

        mvc.perform(get("/api/work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress[*].key", contains("CHAGOK:" + run, "DRAFT:" + draft,
                        "GROUNDING_CHECK:" + check, "MATCH:" + matching, "SOURCE_ANALYSIS:" + job)))
                .andExpect(jsonPath("$.inProgress[0].kind").value("CHAGOK"))
                .andExpect(jsonPath("$.inProgress[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.inProgress[0].label").value("지원 만들어 줘"))
                .andExpect(jsonPath("$.inProgress[0].runId").value(run))
                .andExpect(jsonPath("$.inProgress[0].questionId").value(done))
                .andExpect(jsonPath("$.inProgress[0].applicationId").value(application))
                .andExpect(jsonPath("$.inProgress[0].sourceId", nullValue()))
                .andExpect(jsonPath("$.inProgress[0].finishedAt", nullValue()))
                .andExpect(jsonPath("$.inProgress[1].status").value("ANALYZING"))
                .andExpect(jsonPath("$.inProgress[1].label").value("OO 부트캠프 · 협업 경험"))
                .andExpect(jsonPath("$.inProgress[1].questionId").value(done))
                .andExpect(jsonPath("$.inProgress[1].applicationId").value(application))
                .andExpect(jsonPath("$.inProgress[1].runId", nullValue()))
                .andExpect(jsonPath("$.inProgress[3].questionId").value(matching))
                .andExpect(jsonPath("$.inProgress[4].label").value("바꿔요-회고.md"))
                .andExpect(jsonPath("$.inProgress[4].sourceId").value(source))
                .andExpect(jsonPath("$.inProgress[4].jobId").value(job))
                .andExpect(jsonPath("$.inProgress[4].questionId", nullValue()))
                .andExpect(jsonPath("$.recent[*].kind", contains("MATCH")));
    }

    @Test
    void 끝난_일은_끝난_시각이_최근인_것부터_실패면_오류_코드() throws Exception {
        long job = job(userId, source(userId, "자료"), "FAILED", "AI_TIMEOUT", "3 hours");
        long application = application(userId, null);
        long question = question(userId, application, "문항", "COMPLETED", "2 days", "2 hours");
        long draft = draft(question, "FAILED", "LLM_ERROR", "90 minutes", "1 hour");
        long run = run(userId, "쌓아 줘", null, "DONE", "1 day", "30 minutes");

        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.inProgress", hasSize(0)))
                .andExpect(jsonPath("$.recent[*].key", contains("CHAGOK:" + run, "DRAFT:" + draft,
                        "MATCH:" + question, "SOURCE_ANALYSIS:" + job)))
                .andExpect(jsonPath("$.recent[0].status").value("DONE"))
                .andExpect(jsonPath("$.recent[0].finishedAt").isString())
                .andExpect(jsonPath("$.recent[0].questionId", nullValue()))
                .andExpect(jsonPath("$.recent[0].applicationId", nullValue()))
                .andExpect(jsonPath("$.recent[1].errorCode").value("LLM_ERROR"))
                .andExpect(jsonPath("$.recent[2].label").value("문항"))
                .andExpect(jsonPath("$.recent[2].errorCode", nullValue()))
                .andExpect(jsonPath("$.recent[3].errorCode").value("AI_TIMEOUT"));
    }

    @Test
    void recent_는_24시간_최대_20개이고_그만둔_일이_없다() throws Exception {
        for (int i = 0; i < 25; i++) {
            run(userId, "일 " + i, null, "DONE", "12 hours", i + " minutes");
        }
        run(userId, "그만둔 일", null, "CANCELED", "1 minute", "1 minute");
        run(userId, "하루 지난 일", null, "FAILED", "2 days", "25 hours");
        job(userId, source(userId, "하루 지난 자료"), "COMPLETED", null, "25 hours");

        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.recent", hasSize(20)))
                .andExpect(jsonPath("$.recent[0].label").value("일 0"))
                .andExpect(jsonPath("$.recent[19].label").value("일 19"));
    }

    @Test
    void 확인_대기는_24시간이_지나면_빠지고_돌고_있는_일은_남는다() throws Exception {
        run(userId, "하루 지난 확인", null, "WAITING_CONFIRM", "25 hours", null);
        run(userId, "기다리는 확인", null, "WAITING_CONFIRM", "23 hours", null);
        run(userId, "오래 도는 일", null, "RUNNING", "30 hours", null);

        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.inProgress[*].label", contains("기다리는 확인", "오래 도는 일")));
    }

    @Test
    void 다른_사용자의_일은_보이지_않는다() throws Exception {
        long other = newUser();
        job(other, source(other, "남의 자료"), "ANALYZING", null, "1 minute");
        long application = application(other, "남의 지원");
        long question = question(other, application, "남의 문항", "ANALYZING", "1 minute", null);
        grounding(question, "COMPLETED", null, "1 minute", "1 minute");
        draft(question, "ANALYZING", null, "1 minute", "1 minute");
        run(other, "남의 일", question, "RUNNING", "1 minute", null);
        post(TestLogin.mvc(context, other), "{\"seenAt\":\"2026-11-06T09:00:00+09:00\"}");

        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.seenAt", nullValue()))
                .andExpect(jsonPath("$.inProgress", hasSize(0)))
                .andExpect(jsonPath("$.recent", hasSize(0)));
    }

    @Test
    void 읽어도_일의_상태를_바꾸지_않고_한도를_세지_않는다() throws Exception {
        long job = job(userId, source(userId, "자료"), "ANALYZING", null, "1 minute");

        mvc.perform(get("/api/work")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from analysis_usage where user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("select status from analysis_job where id = ?", String.class, job))
                .isEqualTo("ANALYZING");
    }

    @Test
    void 라벨은_한_줄로_100자까지() throws Exception {
        run(userId, "  첫 줄\n\n둘째 줄  " + "가".repeat(200), null, "RUNNING", "1 minute", null);
        run(userId, "😀".repeat(101), null, "WAITING_CONFIRM", "2 minutes", null);

        String longLabel = "첫 줄 둘째 줄 " + "가".repeat(91);
        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.inProgress[0].label").value(longLabel))
                .andExpect(jsonPath("$.inProgress[1].label").value("😀".repeat(100)));
    }

    @Test
    void 지워진_문항에서_시작한_차곡이_일은_applicationId_가_null() throws Exception {
        long application = application(userId, "OO");
        long question = question(userId, application, "문항", "COMPLETED", "1 hour", "1 hour");
        run(userId, "근거 찾기", question, "RUNNING", "1 minute", null);
        jdbc.update("delete from question where id = ?", question);

        mvc.perform(get("/api/work"))
                .andExpect(jsonPath("$.inProgress[0].questionId").value(question))
                .andExpect(jsonPath("$.inProgress[0].applicationId", nullValue()));
    }

    @Test
    void 끝난_뒤_유형을_고쳐도_끝난_시각이_그대로다() throws Exception {
        long application = application(userId, "OO");
        long question = question(userId, application, "문항", "COMPLETED", "3 hours", "2 hours");
        String before = finishedAt();

        mvc.perform(patch("/api/questions/{id}", question).contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionType\": \"OTHER\"}")).andExpect(status().isOk());

        assertThat(finishedAt()).isEqualTo(before);
    }

    @Test
    void 문항은_끝날_때_시각을_남기고_다시_시도하면_지운다() {
        Question question = new Question(new Application(userId, "OO"), "문항");
        assertThat(question.getFinishedAt()).isNull();

        question.fail("AI_TIMEOUT", "시간 초과");
        assertThat(question.getFinishedAt()).isNotNull();

        question.restart();
        assertThat(question.getFinishedAt()).isNull();

        question.complete(null, List.of(), List.of(), List.of());
        assertThat(question.getFinishedAt()).isNotNull();
    }

    @Test
    void seenAt_은_앞으로만_가고_계정에_남는다() throws Exception {
        post(mvc, "{\"seenAt\":\"2026-11-06T09:58:40+09:00\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seenAt").value("2026-11-06T09:58:40+09:00"));
        // 다른 시간대로 와도 같은 순간으로 비교하고, 응답은 서울 시각이다
        post(mvc, "{\"seenAt\":\"2026-11-06T01:00:00Z\"}")
                .andExpect(jsonPath("$.seenAt").value("2026-11-06T10:00:00+09:00"));
        post(mvc, "{\"seenAt\":\"2026-11-06T09:00:00+09:00\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seenAt").value("2026-11-06T10:00:00+09:00"));
        // 다른 기기(다른 세션)에서도 읽은 것으로 보인다
        TestLogin.mvc(context, userId).perform(get("/api/work"))
                .andExpect(jsonPath("$.seenAt").value("2026-11-06T10:00:00+09:00"));
    }

    @Test
    void seenAt_이_없거나_날짜가_아니면_400() throws Exception {
        for (String body : List.of("{}", "{\"seenAt\":null}", "{\"seenAt\":\"어제\"}")) {
            post(mvc, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
        anonymous.perform(get("/api/work"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        post(anonymous, "{\"seenAt\":\"2026-11-06T09:00:00+09:00\"}")
                .andExpect(status().isUnauthorized());
    }

    private ResultActions post(MockMvc caller, String body) throws Exception {
        return caller.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/work/seen")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String finishedAt() throws Exception {
        String body = mvc.perform(get("/api/work")).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.recent[0].finishedAt");
    }

    private long newUser() {
        return users.save(new AppUser("work-" + System.nanoTime(), "hash", null)).getId();
    }

    private long source(long user, String name) {
        return insert("insert into source (user_id, kind, name, text) values (?, 'TEXT', ?, '본문') returning id", user, name);
    }

    private long job(long user, long source, String status, String errorCode, String updatedAgo) {
        return insert("""
                insert into analysis_job (user_id, source_id, status, error_code, created_at, updated_at)
                values (?, ?, ?, ?, now() - interval '2 days', now() - ?::interval) returning id
                """, user, source, status, errorCode, updatedAgo);
    }

    private long application(long user, String organization) {
        return insert("insert into application (user_id, organization) values (?, ?) returning id", user, organization);
    }

    private long question(long user, long application, String text, String status, String updatedAgo, String finishedAgo) {
        return insert("""
                insert into question (user_id, application_id, text, status, updated_at, finished_at)
                values (?, ?, ?, ?, now() - ?::interval, now() - ?::interval) returning id
                """, user, application, text, status, updatedAgo, finishedAgo);
    }

    private long grounding(long question, String status, String errorCode, String createdAgo, String updatedAgo) {
        return insert("""
                insert into grounding_check (question_id, text, status, error_code, created_at, updated_at)
                values (?, '글', ?, ?, now() - ?::interval, now() - ?::interval) returning id
                """, question, status, errorCode, createdAgo, updatedAgo);
    }

    private long draft(long question, String status, String errorCode, String createdAgo, String updatedAgo) {
        return insert("""
                insert into draft (question_id, status, error_code, created_at, updated_at)
                values (?, ?, ?, now() - ?::interval, now() - ?::interval) returning id
                """, question, status, errorCode, createdAgo, updatedAgo);
    }

    private long run(long user, String text, Long question, String status, String createdAgo, String finishedAgo) {
        return insert("""
                insert into chagok_run (user_id, text, question_id, status, steps, created_at, finished_at)
                values (?, ?, ?, ?, '[]', now() - ?::interval, now() - ?::interval) returning id
                """, user, text, question, status, createdAgo, finishedAgo);
    }

    private long insert(String sql, Object... params) {
        return jdbc.queryForObject(sql, Long.class, params);
    }
}

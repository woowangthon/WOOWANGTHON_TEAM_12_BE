package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.account.AuthService;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.source.SourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/auth-api.md — 가입 · 로그인 · 로그아웃 · 내 정보, 로그인해야 쓰는 API, 하루 분석 한도(SPEC S-14 · S-15).
 * AI 서버는 없는 주소로 둔다 — 분석은 AI_UNAVAILABLE 로 실패하지만 횟수는 센다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.test-account.password=judge-password", "app.test-account.daily-limit=7",
        "app.ai.base-url=http://127.0.0.1:9", "app.ai.timeout-seconds=1"})
class AuthApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    ActivityRepository activities;

    @Autowired
    SourceRepository sources;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("truncate table analysis_usage, analysis_job_activity, analysis_job, supplement, citation, experience, activity, "
                + "source_section, source");
        jdbc.update("delete from app_user where login_id is not null and login_id <> 'test'");
    }

    // --- 로그인해야 쓴다 ---

    @Test
    void 로그인하지_않으면_401() throws Exception {
        expectError(mvc.perform(get("/api/activities")), 401, "UNAUTHENTICATED");
        expectError(mvc.perform(get("/api/auth/me")), 401, "UNAUTHENTICATED");
        expectError(postJson(null, "/api/sources", "{\"kind\":\"TEXT\",\"text\":\"메모\"}"), 401, "UNAUTHENTICATED");
        assertThat(sources.count()).isZero();
    }

    @Test
    void CORS_사전_요청은_로그인_없이_통과한다() throws Exception {
        mvc.perform(options("/api/activities")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
    }

    // --- 가입 · 로그인 · 로그아웃 ---

    @Test
    void 가입하면_바로_로그인되고_아이디는_소문자로_맞춘다() throws Exception {
        MockHttpSession session = session(postJson(null, "/api/auth/signup",
                "{\"loginId\":\" Mina_01 \",\"password\":\"password-1\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("mina_01"))
                .andExpect(jsonPath("$.dailyAnalysis.used").value(0))
                .andExpect(jsonPath("$.dailyAnalysis.limit").value(20)));

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("mina_01"));
        mvc.perform(get("/api/activities").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities.length()").value(0));
    }

    @Test
    void 가입_검사() throws Exception {
        signup("mina", "password-1");
        expectError(postJson(null, "/api/auth/signup", "{\"loginId\":\"MINA\",\"password\":\"password-2\"}"),
                409, "LOGIN_ID_TAKEN");
        expectError(postJson(null, "/api/auth/signup", "{\"loginId\":\"ab\",\"password\":\"password-1\"}"),
                400, "INVALID_REQUEST");
        expectError(postJson(null, "/api/auth/signup", "{\"loginId\":\"한글아이디\",\"password\":\"password-1\"}"),
                400, "INVALID_REQUEST");
        expectError(postJson(null, "/api/auth/signup", "{\"loginId\":\"jun\",\"password\":\"short\"}"),
                400, "INVALID_REQUEST");
        expectError(postJson(null, "/api/auth/signup", "{\"loginId\":\"jun\",\"password\":\"" + "가".repeat(25) + "\"}"),
                400, "INVALID_REQUEST");
        expectError(postJson(null, "/api/auth/signup", "{\"loginId\":\"jun\"}"), 400, "INVALID_REQUEST");
    }

    @Test
    void 로그인_로그아웃() throws Exception {
        signup("mina", "password-1");

        expectError(postJson(null, "/api/auth/login", "{\"loginId\":\"mina\",\"password\":\"wrong-password\"}"),
                401, "INVALID_CREDENTIALS");
        expectError(postJson(null, "/api/auth/login", "{\"loginId\":\"nobody\",\"password\":\"password-1\"}"),
                401, "INVALID_CREDENTIALS");
        expectError(postJson(null, "/api/auth/login", "{}"), 401, "INVALID_CREDENTIALS");

        MockHttpSession session = session(postJson(null, "/api/auth/login",
                "{\"loginId\":\"Mina\",\"password\":\"password-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("mina")));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
        expectError(mvc.perform(get("/api/auth/me").session(session)), 401, "UNAUTHENTICATED");
        // 로그인 안 한 채 로그아웃해도 된다
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void 로그인하면_세션_ID_가_바뀐다() throws Exception {
        signup("mina", "password-1");
        MockHttpSession before = new MockHttpSession();
        String beforeId = before.getId();
        MockHttpSession after = session(postJson(before, "/api/auth/login",
                "{\"loginId\":\"mina\",\"password\":\"password-1\"}").andExpect(status().isOk()));
        assertThat(after.getId()).isNotEqualTo(beforeId);
    }

    // --- 사용자별로 나뉜다 ---

    @Test
    void 다른_사용자의_활동은_보이지_않는다() throws Exception {
        MockHttpSession mina = signup("mina", "password-1");
        MockHttpSession jun = signup("jun", "password-1");
        long minaId = JsonPath.<Number>read(me(mina), "$.id").longValue();
        long activityId = activities.save(new Activity(minaId, "미나의 활동", new Period(null, null, false))).getId();

        mvc.perform(get("/api/activities").session(mina)).andExpect(jsonPath("$.activities.length()").value(1));
        mvc.perform(get("/api/activities").session(jun)).andExpect(jsonPath("$.activities.length()").value(0));
        expectError(mvc.perform(get("/api/activities/" + activityId).session(jun)), 404, "ACTIVITY_NOT_FOUND");
    }

    // --- 하루 분석 한도 ---

    @Test
    void 한도를_넘으면_분석을_시작하지_않고_이유를_돌려준다() throws Exception {
        MockHttpSession session = signup("mina", "password-1");
        long userId = JsonPath.<Number>read(me(session), "$.id").longValue();
        jdbc.update("update app_user set daily_analysis_limit = 2 where id = ?", userId);

        long first = submit(session);
        long second = submit(session);
        expectError(postJson(session, "/api/sources", "{\"kind\":\"TEXT\",\"text\":\"세 번째 메모\"}"),
                429, "DAILY_LIMIT_EXCEEDED");
        assertThat(sources.count()).isEqualTo(2);

        // 다시 시도도 한 번으로 센다
        awaitFailed(session, first);
        awaitFailed(session, second);
        expectError(mvc.perform(post("/api/jobs/" + first + "/retry").session(session)), 429, "DAILY_LIMIT_EXCEEDED");
        expectError(postJson(session, "/api/questions", "{\"text\":\"지원 동기를 쓰시오\"}"), 429, "DAILY_LIMIT_EXCEEDED");

        String me = me(session);
        assertThat(JsonPath.<Integer>read(me, "$.dailyAnalysis.used")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(me, "$.dailyAnalysis.limit")).isEqualTo(2);

        // 다른 사용자는 따로 센다
        MockHttpSession jun = signup("jun", "password-1");
        awaitFailed(jun, submit(jun));
    }

    @Test
    void 요청이_틀려_시작하지_못한_분석은_세지_않는다() throws Exception {
        MockHttpSession session = signup("mina", "password-1");
        expectError(postJson(session, "/api/sources", "{\"kind\":\"TEXT\",\"text\":\"  \"}"), 400, "EMPTY_TEXT");
        expectError(postJson(session, "/api/sources", "{\"kind\":\"FILE\",\"name\":\"a.hwp\",\"text\":\"x\"}"),
                400, "UNSUPPORTED_FILE_TYPE");
        expectError(postJson(session, "/api/sources", "{\"kind\":\"FILE\",\"name\":\"a.pdf\",\"text\":\"x\"}"),
                400, "INVALID_SECTIONS");
        assertThat(JsonPath.<Integer>read(me(session), "$.dailyAnalysis.used")).isZero();
    }

    @Test
    void 한도가_0_이면_한_번도_못_한다() throws Exception {
        MockHttpSession session = signup("mina", "password-1");
        long userId = JsonPath.<Number>read(me(session), "$.id").longValue();
        jdbc.update("update app_user set daily_analysis_limit = 0 where id = ?", userId);
        expectError(postJson(session, "/api/sources", "{\"kind\":\"TEXT\",\"text\":\"메모\"}"), 429, "DAILY_LIMIT_EXCEEDED");
        assertThat(sources.count()).isZero();
    }

    // --- 테스트 계정 (SAMPLE(test-account)) ---

    @Test
    void 테스트_계정은_설정한_비밀번호와_넉넉한_한도로_생긴다() throws Exception {
        MockHttpSession session = session(postJson(null, "/api/auth/login",
                "{\"loginId\":\"test\",\"password\":\"judge-password\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(me(session), "$.dailyAnalysis.limit")).isEqualTo(7);

        // 다시 켜면(설정값이 바뀌면) 비밀번호 · 한도를 맞춘다
        auth.ensureAccount("test", "new-judge-password", 9);
        expectError(postJson(null, "/api/auth/login", "{\"loginId\":\"test\",\"password\":\"judge-password\"}"),
                401, "INVALID_CREDENTIALS");
        session = session(postJson(null, "/api/auth/login",
                "{\"loginId\":\"test\",\"password\":\"new-judge-password\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(me(session), "$.dailyAnalysis.limit")).isEqualTo(9);
        auth.ensureAccount("test", "judge-password", 7);
    }

    // --- 도움 ---

    private MockHttpSession signup(String loginId, String password) throws Exception {
        return session(postJson(null, "/api/auth/signup",
                "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isCreated()));
    }

    private long submit(MockHttpSession session) throws Exception {
        String body = postJson(session, "/api/sources", "{\"kind\":\"TEXT\",\"text\":\"메모\"}")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.jobId").longValue();
    }

    private void awaitFailed(MockHttpSession session, long jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            String job = mvc.perform(get("/api/jobs/" + jobId).session(session))
                    .andReturn().getResponse().getContentAsString();
            if ("FAILED".equals(JsonPath.read(job, "$.status"))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("작업 " + jobId + " 이(가) 실패로 끝나지 않았습니다");
    }

    private String me(MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockHttpSession session(ResultActions result) {
        return (MockHttpSession) result.andReturn().getRequest().getSession(false);
    }

    private ResultActions postJson(MockHttpSession session, String url, String json) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(session == null ? request : request.session(session));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}

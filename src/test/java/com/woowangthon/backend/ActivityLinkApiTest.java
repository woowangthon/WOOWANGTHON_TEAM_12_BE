package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/experience-api.md 「이어짐 제안 받기」 · 「이어짐 정하기」 와 ai-api.md 「POST /link-suggestions」(S-35).
 * AI 서버 자리에 가짜 HTTP 서버를 띄워 응답 · 오류 · 늦은 답을 돌려준다(테스트 전용 — 앱은 진짜 AI 서버만 부른다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.sync-timeout-seconds=1")
class ActivityLinkApiTest {

    record FakeReply(int status, String body, long delayMillis) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile FakeReply reply;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        FAKE_AI.createContext("/link-suggestions", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            FakeReply r = reply;
            if (r.delayMillis() > 0) {
                try {
                    Thread.sleep(r.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(r.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // 제한 시간 테스트에서 BE 가 먼저 끊은 경우
            }
            exchange.close();
        });
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ActivityRepository activities;

    @Autowired
    ExperienceRepository experiences;

    @Autowired
    AppUserRepository users;

    MockMvc mvc;

    long studyId;
    long appId;
    long earlierId;
    long noDateId;
    long chat;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        Activity study = new Activity(TestLogin.USER_ID, "알고리즘 스터디", new Period("2024-09", null, true));
        study.changeNote("문제만 풀다 보니 직접 서비스를 만들어 보고 싶어졌다");
        studyId = activities.save(study).getId();
        Activity app = activities.save(new Activity(TestLogin.USER_ID, "바꿔요", new Period("2025-03", "2025-06", false)));
        appId = app.getId();
        chat = experiences.save(new Experience(app, "채팅을 WebSocket 으로 바꿨다", List.of(Competency.PROBLEM_SOLVING),
                CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0)).getId();
        earlierId = activities.save(new Activity(TestLogin.USER_ID, "앞선 활동", new Period("2023-01", null, false)))
                .getId();
        noDateId = activities.save(new Activity(TestLogin.USER_ID, "날짜 없는 활동", new Period(null, null, false))).getId();
        reply = suggesting(appId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table analysis_usage, activity_link, experience, activity cascade");
        jdbc.update("delete from app_user where login_id = 'other'");
    }

    private static FakeReply suggesting(long... toActivityIds) {
        StringBuilder items = new StringBuilder();
        for (long id : toActivityIds) {
            items.append(items.isEmpty() ? "" : ",")
                    .append("{\"toActivityId\": ").append(id).append(", \"reason\": \"닿아 보여요 ").append(id).append("\"}");
        }
        return new FakeReply(200, "{\"suggestions\": [" + items + "], \"model\": \"gpt-test\"}", 0);
    }

    private ResultActions suggest(long activityId) throws Exception {
        return mvc.perform(post("/api/activities/{id}/link-suggestions", activityId));
    }

    private ResultActions decide(long linkId, String json) throws Exception {
        return mvc.perform(patch("/api/activity-links/{id}", linkId).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long suggestedLink(long fromId, long toId) throws Exception {
        reply = suggesting(toId);
        String body = suggest(fromId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.links[0].id")).longValue();
    }

    private long activityWithNote(String name, String start) {
        Activity activity = new Activity(TestLogin.USER_ID, name, new Period(start, null, false));
        activity.changeNote(name + " 계기");
        return activities.save(activity).getId();
    }

    private void merge(long keepId, long goneId) throws Exception {
        mvc.perform(post("/api/activities/{id}/merge", keepId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromActivityId\": " + goneId + "}")).andExpect(status().isOk());
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private int usedToday() {
        return jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage where user_id = ?",
                Integer.class, TestLogin.USER_ID);
    }

    private int linkCount() {
        return jdbc.queryForObject("select count(*) from activity_link", Integer.class);
    }

    @Test
    void 제안을_받으면_SUGGESTED_로_저장하고_활동_세부_양쪽에_실린다() throws Exception {
        suggest(studyId).andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].fromActivityId").value(studyId))
                .andExpect(jsonPath("$.links[0].fromActivityName").value("알고리즘 스터디"))
                .andExpect(jsonPath("$.links[0].toActivityId").value(appId))
                .andExpect(jsonPath("$.links[0].toActivityName").value("바꿔요"))
                .andExpect(jsonPath("$.links[0].reason").value("닿아 보여요 " + appId))
                .andExpect(jsonPath("$.links[0].status").value("SUGGESTED"))
                .andExpect(jsonPath("$.links[0].createdAt").value(endsWith("+09:00")));
        assertThat(usedToday()).isEqualTo(1);

        // ai-api.md 요청 모양 — 뒤에 시작한 활동만, 경험은 id · content 만, 후보의 note 는 null
        String sent = RECEIVED.getFirst();
        assertThat(JsonPath.<Integer>read(sent, "$.from.id")).isEqualTo((int) studyId);
        assertThat(JsonPath.<String>read(sent, "$.from.note")).startsWith("문제만 풀다");
        assertThat(JsonPath.<List<Integer>>read(sent, "$.candidates[*].id")).containsExactly((int) appId);
        assertThat(JsonPath.<String>read(sent, "$.candidates[0].period.start")).isEqualTo("2025-03");
        assertThat(JsonPath.<Object>read(sent, "$.candidates[0].note")).isNull();
        assertThat(JsonPath.<java.util.Map<String, Object>>read(sent, "$.candidates[0].experiences[0]"))
                .containsOnlyKeys("id", "content")
                .containsEntry("id", (int) chat);

        mvc.perform(get("/api/activities/{id}", studyId))
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].toActivityId").value(appId));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].fromActivityId").value(studyId));
        // 이름은 응답 때 지금 값
        activities.findById(appId).ifPresent(a -> {
            a.rename("바꿔요 2");
            activities.save(a);
        });
        mvc.perform(get("/api/activities/{id}", studyId)).andExpect(jsonPath("$.links[0].toActivityName").value("바꿔요 2"));
    }

    @Test
    void 후보는_같거나_뒤에_시작한_활동을_가까운_순으로_20개까지() throws Exception {
        long sameDay = activities.save(new Activity(TestLogin.USER_ID, "같은 달", new Period("2024-09", null, false))).getId();
        for (int i = 0; i < 25; i++) {
            activities.save(new Activity(TestLogin.USER_ID, "먼 활동 " + i, new Period("2030-01", null, false)));
        }
        reply = suggesting();

        suggest(studyId).andExpect(status().isOk()).andExpect(jsonPath("$.links.length()").value(0));

        List<Integer> ids = JsonPath.read(RECEIVED.getFirst(), "$.candidates[*].id");
        assertThat(ids).hasSize(20).startsWith((int) sameDay, (int) appId).doesNotContain((int) studyId, (int) earlierId,
                (int) noDateId);
    }

    @Test
    void 후보가_없으면_AI_를_부르지_않고_세지도_않는다() throws Exception {
        long last = activityWithNote("맨 뒤", "2031-01");
        suggest(last).andExpect(status().isOk()).andExpect(jsonPath("$.links.length()").value(0));

        long dateless = activityWithNote("날짜 없음", null);
        suggest(dateless).andExpect(status().isOk()).andExpect(jsonPath("$.links.length()").value(0));

        assertThat(RECEIVED).isEmpty();
        assertThat(usedToday()).isZero();
    }

    @Test
    void AI_가_못_찾아도_실패가_아니다() throws Exception {
        reply = suggesting();
        suggest(studyId).andExpect(status().isOk()).andExpect(jsonPath("$.links.length()").value(0));
        assertThat(usedToday()).isEqualTo(1);
        assertThat(linkCount()).isZero();
    }

    @Test
    void 이미_있는_쌍은_상태와_방향_상관없이_후보에서_빠진다() throws Exception {
        long reverse = activityWithNote("뒤 활동", "2026-01");
        long rejected = suggestedLink(studyId, appId);
        decide(rejected, "{\"status\": \"REJECTED\"}").andExpect(status().isOk());
        // 같은 달에 시작해 서로 후보가 되는 두 활동 — 한 방향으로 생기면 반대 방향으로도 제안하지 않는다
        long twin = activityWithNote("같은 달 활동", "2026-01");
        suggestedLink(reverse, twin);
        RECEIVED.clear();

        reply = suggesting();
        suggest(studyId).andExpect(status().isOk());
        assertThat(JsonPath.<List<Integer>>read(RECEIVED.getFirst(), "$.candidates[*].id"))
                .containsExactlyInAnyOrder((int) reverse, (int) twin);

        RECEIVED.clear();
        activities.save(new Activity(TestLogin.USER_ID, "새 활동", new Period("2027-01", null, false)));
        suggest(twin).andExpect(status().isOk());
        assertThat(JsonPath.<List<Integer>>read(RECEIVED.getFirst(), "$.candidates[*].id")).doesNotContain((int) reverse);
    }

    @Test
    void 계약을_어긴_AI_응답은_저장하지_않고_502() throws Exception {
        long second = activityWithNote("둘째", "2025-05");
        long third = activityWithNote("셋째", "2025-06");
        long fourth = activityWithNote("넷째", "2025-07");
        List<FakeReply> broken = List.of(
                suggesting(appId, earlierId),
                suggesting(appId, appId),
                suggesting(appId, second, third, fourth),
                new FakeReply(200, "{\"model\": \"gpt-test\"}", 0),
                new FakeReply(200, "{\"suggestions\": [{\"toActivityId\": " + appId + ", \"reason\": \" \"}]}", 0));
        for (FakeReply r : broken) {
            reply = r;
            expectError(suggest(studyId), 502, "AI_FAILED");
        }
        assertThat(linkCount()).isZero();
        assertThat(usedToday()).isEqualTo(broken.size());
    }

    @Test
    void AI_실패는_정한_답으로_덮지_않고_오류로_돌려주고_횟수는_센다() throws Exception {
        reply = new FakeReply(502, "{\"error\": {\"code\": \"LLM_ERROR\", \"message\": \"x\"}}", 0);
        expectError(suggest(studyId), 502, "AI_FAILED");

        reply = new FakeReply(200, "{\"suggestions\": []}", 2000);
        expectError(suggest(studyId), 504, "AI_TIMEOUT");

        assertThat(linkCount()).isZero();
        assertThat(usedToday()).isEqualTo(2);
    }

    @Test
    void 제안_오류() throws Exception {
        expectError(suggest(999_999), 404, "ACTIVITY_NOT_FOUND");
        expectError(suggest(appId), 409, "NOTE_REQUIRED");

        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        Activity others = new Activity(otherId, "남의 활동", new Period("2025-01", null, false));
        others.changeNote("남의 계기");
        expectError(suggest(activities.save(others).getId()), 404, "ACTIVITY_NOT_FOUND");

        jdbc.update("update app_user set daily_analysis_limit = 0 where id = ?", TestLogin.USER_ID);
        expectError(suggest(studyId), 429, "DAILY_LIMIT_EXCEEDED");
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 맞아요_아니에요로_정하고_REJECTED_는_세부에서_빠진다() throws Exception {
        long link = suggestedLink(studyId, appId);

        decide(link, "{\"status\": \"CONFIRMED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reason").value("닿아 보여요 " + appId));
        decide(link, "{\"status\": \"CONFIRMED\"}").andExpect(status().isOk());
        expectError(decide(link, "{\"status\": \"REJECTED\"}"), 409, "LINK_ALREADY_DECIDED");
        mvc.perform(get("/api/activities/{id}", studyId)).andExpect(jsonPath("$.links[0].status").value("CONFIRMED"));

        long later = activityWithNote("뒤 활동", "2026-01");
        long second = suggestedLink(studyId, later);
        decide(second, "{\"status\": \"REJECTED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        expectError(decide(second, "{\"status\": \"CONFIRMED\"}"), 409, "LINK_ALREADY_DECIDED");
        mvc.perform(get("/api/activities/{id}", studyId)).andExpect(jsonPath("$.links.length()").value(1));
        mvc.perform(get("/api/activities/{id}", later)).andExpect(jsonPath("$.links.length()").value(0));
    }

    @Test
    void 정하기_오류() throws Exception {
        long link = suggestedLink(studyId, appId);
        for (String body : List.of("{}", "{\"status\": \"SUGGESTED\"}", "{\"status\": \"그냥\"}", "{\"status\": null}",
                "{\"status\": 1}")) {
            expectError(decide(link, body), 400, "INVALID_REQUEST");
        }
        expectError(decide(999_999, "{\"status\": \"CONFIRMED\"}"), 404, "LINK_NOT_FOUND");

        long otherId = users.save(new AppUser("other", "hash", null)).getId();
        MockMvc other = TestLogin.mvc(context, otherId);
        expectError(other.perform(patch("/api/activity-links/{id}", link).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"CONFIRMED\"}")), 404, "LINK_NOT_FOUND");
    }

    @Test
    void 활동을_지우면_이어짐도_지워진다() throws Exception {
        suggestedLink(studyId, appId);
        jdbc.update("delete from experience where activity_id = ?", appId);
        activities.deleteById(appId);
        assertThat(linkCount()).isZero();
    }

    @Test
    void 합치면_남길_활동으로_옮기고_자기_자신을_가리키면_지운다() throws Exception {
        long keep = activityWithNote("남길 활동", "2025-02");
        long moved = suggestedLink(studyId, appId);
        long self = suggestedLink(studyId, keep);

        // 스터디를 남길 활동에 합친다 — 스터디→바꿔요 는 남길→바꿔요 로, 스터디→남길 은 자기 자신이 되어 사라진다
        mvc.perform(post("/api/activities/{id}/merge", keep).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromActivityId\": " + studyId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].id").value(moved))
                .andExpect(jsonPath("$.links[0].fromActivityId").value(keep))
                .andExpect(jsonPath("$.links[0].fromActivityName").value("남길 활동"));
        expectError(decide(self, "{\"status\": \"CONFIRMED\"}"), 404, "LINK_NOT_FOUND");
        assertThat(linkCount()).isEqualTo(1);

        // to 쪽도 옮긴다
        long keepTarget = activityWithNote("다른 목적지", "2025-04");
        merge(keepTarget, appId);
        mvc.perform(get("/api/activities/{id}", keepTarget))
                .andExpect(jsonPath("$.links[0].id").value(moved))
                .andExpect(jsonPath("$.links[0].toActivityId").value(keepTarget));
    }

    /** a → target 와 b → target 을 같은 쌍으로 합쳤을 때 남는 이어짐의 id. */
    private long survivorOf(String firstStatus, String secondStatus) throws Exception {
        long a = activityWithNote("활동 A", "2024-01");
        long b = activityWithNote("활동 B", "2024-02");
        long target = activityWithNote("목적지", "2029-01");
        long first = suggestedLink(a, target);
        long second = suggestedLink(b, target);
        if (firstStatus != null) {
            decide(first, "{\"status\": \"" + firstStatus + "\"}").andExpect(status().isOk());
        }
        if (secondStatus != null) {
            decide(second, "{\"status\": \"" + secondStatus + "\"}").andExpect(status().isOk());
        }
        merge(a, b);
        List<Long> left = jdbc.queryForList("select id from activity_link where to_activity_id = ?", Long.class, target);
        assertThat(left).hasSize(1);
        assertThat(jdbc.queryForObject("select from_activity_id from activity_link where id = ?", Long.class,
                left.getFirst())).isEqualTo(a);
        return left.getFirst() == first ? 1 : 2;
    }

    @Test
    void 합쳐서_같은_쌍이_둘이면_REJECTED_CONFIRMED_먼저_생긴_것_순으로_남긴다() throws Exception {
        assertThat(survivorOf(null, "REJECTED")).isEqualTo(2);
        assertThat(survivorOf("CONFIRMED", "REJECTED")).isEqualTo(2);
        assertThat(survivorOf("REJECTED", "CONFIRMED")).isEqualTo(1);
        assertThat(survivorOf(null, "CONFIRMED")).isEqualTo(2);
        assertThat(survivorOf("CONFIRMED", null)).isEqualTo(1);
        assertThat(survivorOf(null, null)).isEqualTo(1);
    }

    @Test
    void 그새_같은_쌍이_생겼으면_그_제안만_건너뛴다() throws Exception {
        long later = activityWithNote("뒤 활동", "2026-01");
        reply = new FakeReply(200, suggesting(appId, later).body(), 300);
        Thread slow = new Thread(() -> {
            try {
                TestLogin.mvc(context).perform(post("/api/activities/{id}/link-suggestions", studyId));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        slow.start();
        while (RECEIVED.isEmpty()) {
            Thread.onSpinWait();
        }
        jdbc.update("insert into activity_link (from_activity_id, to_activity_id, reason, status) values (?, ?, '먼저', 'REJECTED')",
                studyId, appId);
        slow.join();

        assertThat(linkCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from activity_link where to_activity_id = ?", String.class, appId))
                .isEqualTo("REJECTED");
    }
}

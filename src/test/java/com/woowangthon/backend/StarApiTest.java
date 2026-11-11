package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.InterruptedStarCleaner;
import com.woowangthon.backend.experience.StarRunner;
import com.woowangthon.backend.experience.StarSweeper;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
 * BE#175 — experience-api.md 「STAR 칸 Star」 · 「STAR 칸 — 정리 · 빈칸 답하기 · 칸 고치기」 · 활동 요약 starGapCount · starUnsortedCount 와
 * ai-api.md 「POST /star」. AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 정리가 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StarApiTest {

    record Reply(int status, String body, long delayMillis) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> STARRED = new CopyOnWriteArrayList<>();
    // 동시에 받고 있는 /star 수와 그 최대 — 동시 호출 상한(BE#180) 확인용
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
    static volatile Function<String, Reply> starReply;
    static volatile String extractReply;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        FAKE_AI.createContext("/star", exchange -> {
            String body = read(exchange);
            STARRED.add(body);
            MAX_IN_FLIGHT.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
            try {
                send(exchange, starReply.apply(body));
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        });
        FAKE_AI.createContext("/extract", exchange -> {
            read(exchange);
            send(exchange, new Reply(200, extractReply, 0));
        });
        FAKE_AI.start();
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String CHAT_QUOTE = "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.";
    static final String CHAT_ANSWER = "서버에 STOMP 엔드포인트를 만들고,\n앱의 3초 폴링 코드를 구독 방식으로 바꿨다";
    static final String DEPLOY_QUOTE = "배포는 팀원이 맡아 AWS 에 올렸다.";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityRepository activities;
    @Autowired ExperienceRepository experiences;
    @Autowired CitationRepository citations;
    @Autowired SupplementRepository supplements;
    @Autowired SourceRepository sources;
    @Autowired InterruptedStarCleaner cleaner;
    @Autowired StarSweeper sweeper;
    @Autowired StarRunner starRunner;

    MockMvc mvc;
    long app;
    long chat;
    long deploy;
    long chatCitation;
    long missedCitation;
    long chatSupplement;
    long pendingSupplement;
    long deployCitation;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        STARRED.clear();
        MAX_IN_FLIGHT.set(0);
        Source source = sources.save(new Source(TestLogin.USER_ID, SourceKind.TEXT, "바꿔요-회고.md",
                CHAT_QUOTE + "\n" + DEPLOY_QUOTE));
        Activity activity = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        app = activity.getId();
        Experience c = experiences.save(new Experience(activity, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING), CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0));
        chat = c.getId();
        chatCitation = citations.save(new Citation(c, source, CHAT_QUOTE, true, 1, 1)).getId();
        missedCitation = citations.save(new Citation(c, source, "원본에서 못 찾은 발췌", false, null, null)).getId();
        chatSupplement = supplements.save(new Supplement(c, null, null, "직접 고친 부분은 어디였나요?", CHAT_ANSWER)).getId();
        Supplement pending = new Supplement(c, null, null, "외부 AI 가 전한 질문", "외부 AI 가 전한 답");
        pending.markFromExternalAi(9001L, "claude-ai");
        pendingSupplement = supplements.save(pending).getId();
        Experience d = experiences.save(new Experience(activity, "AWS 에 배포했다", List.of(), CompetenciesBy.AI,
                Actor.TEAM, SourceStatus.SOURCE_CONFIRMED, 1));
        deploy = d.getId();
        deployCitation = citations.save(new Citation(d, source, DEPLOY_QUOTE, true, 2, 2)).getId();
        starReply = body -> new Reply(200, contractReply(), 0);
    }

    @AfterEach
    void cleanUp() throws Exception {
        // 뒤에서 도는 정리가 남아 있으면 끝날 때까지 기다린다(지운 뒤 저장하지 않게)
        awaitNotAnalyzing();
        StarIdle.await(starRunner);
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table experience_star_slot, new_fact_candidate, answer_dismissed, answer, answer_draft, draft_sentence_evidence, draft_sentence, draft_omitted, draft_guideline, draft_experience, draft, grounding_sentence_evidence, grounding_sentence, grounding_check, question_recommendation_evidence, question_recommendation, question_requirement, "
                + "supplement, clarification, question, application_posting_line, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity_link, activity, source_section, source");
    }

    // 계약 예시 모양 — 채팅: S 근거 일부 · T 빈칸 · A 보완 전부(줄바꿈은 공백 하나로 대조) · R 원문에 없는 글(어긋남).
    // 배포(TEAM): T 를 근거로 채움(어긋남 — 수행 주체가 나가 아니다) · 나머지 빈칸
    private String contractReply() {
        return """
                {"experiences":[
                  {"id":%1$d,"slots":[
                    {"slot":"S","sourceType":"CITATION","sourceId":%3$d,"text":"채팅 메시지가 늦게 도착하는 문제가 있었다","question":null},
                    {"slot":"T","sourceType":null,"sourceId":null,"text":null,"question":"이 문제를 고치는 일은 누가 맡았나요?"},
                    {"slot":"A","sourceType":"SUPPLEMENT","sourceId":%4$d,"text":"서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다","question":null},
                    {"slot":"R","sourceType":"CITATION","sourceId":%3$d,"text":"응답 시간이 절반으로 줄었다","question":"바꾼 뒤 무엇이 달라졌나요?"}]},
                  {"id":%2$d,"slots":[
                    {"slot":"S","sourceType":null,"sourceId":null,"text":null,"question":null},
                    {"slot":"T","sourceType":"CITATION","sourceId":%5$d,"text":"배포는 팀원이 맡아","question":null},
                    {"slot":"A","sourceType":"CITATION","sourceId":%5$d,"text":"AWS 에 올렸다","question":null},
                    {"slot":"R","sourceType":null,"sourceId":null,"text":null,"question":"배포한 뒤 무엇이 달라졌나요?"}]}],
                 "model":"test-model"}""".formatted(chat, deploy, chatCitation, chatSupplement, deployCitation);
    }

    @Test
    void STAR_로_정리하면_정리_중을_보이고_칸을_대조해_저장한다() throws Exception {
        // 정리 전 — 칸 없음 · 요약은 둘 다 정리 안 함
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("NONE"))
                .andExpect(jsonPath("$.experiences[0].star.slots").isEmpty());
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[0].starGapCount").value(0))
                .andExpect(jsonPath("$.activities[0].starUnsortedCount").value(2));

        starReply = body -> new Reply(200, contractReply(), 300);
        sort().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("ANALYZING"))
                .andExpect(jsonPath("$.experiences[0].star.slots").isEmpty())
                .andExpect(jsonPath("$.experiences[1].star.status").value("ANALYZING"));
        String done = awaitNotAnalyzing();

        // 채팅 — S · A 는 찬 칸(질문 없음), T 는 빈칸 + 질문, R 은 원문에 없는 글이라 빈칸(질문은 남는다)
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.status")).isEqualTo("READY");
        assertThat(JsonPath.<List<String>>read(done, "$.experiences[0].star.slots[*].slot")).containsExactly("S", "T", "A", "R");
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.slots[0].sourceType")).isEqualTo("CITATION");
        assertThat(((Number) JsonPath.read(done, "$.experiences[0].star.slots[0].sourceId")).longValue()).isEqualTo(chatCitation);
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.slots[0].text")).isEqualTo("채팅 메시지가 늦게 도착하는 문제가 있었다");
        assertThat((Object) JsonPath.read(done, "$.experiences[0].star.slots[0].question")).isNull();
        assertThat((Object) JsonPath.read(done, "$.experiences[0].star.slots[1].text")).isNull();
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.slots[1].question")).isEqualTo("이 문제를 고치는 일은 누가 맡았나요?");
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.slots[2].sourceType")).isEqualTo("SUPPLEMENT");
        assertThat(((Number) JsonPath.read(done, "$.experiences[0].star.slots[2].sourceId")).longValue()).isEqualTo(chatSupplement);
        assertThat((Object) JsonPath.read(done, "$.experiences[0].star.slots[3].sourceType")).isNull();
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.slots[3].question")).isEqualTo("바꾼 뒤 무엇이 달라졌나요?");
        // 배포(TEAM) — T 는 근거로 채우지 않는다, A 는 근거 일부
        assertThat((Object) JsonPath.read(done, "$.experiences[1].star.slots[1].sourceType")).isNull();
        assertThat(JsonPath.<String>read(done, "$.experiences[1].star.slots[2].text")).isEqualTo("AWS 에 올렸다");

        // AI 요청 — 원본에서 찾은 근거 · 사용자가 정한 보완만
        String sent = STARRED.getFirst();
        assertThat(JsonPath.<List<Number>>read(sent, "$.experiences[*].id")).extracting(Number::longValue)
                .containsExactly(chat, deploy);
        assertThat(JsonPath.<List<Number>>read(sent, "$.experiences[0].citations[*].id")).extracting(Number::longValue)
                .containsExactly(chatCitation);
        assertThat(JsonPath.<List<Number>>read(sent, "$.experiences[0].supplements[*].id")).extracting(Number::longValue)
                .containsExactly(chatSupplement);
        assertThat(JsonPath.<String>read(sent, "$.experiences[1].actor")).isEqualTo("TEAM");

        // 분석 횟수는 세지 않는다(미결정 31) · 빈칸 수(채팅 T · R + 배포 S · T · R = 5) · 다 정리됨
        assertThat(usage()).isZero();
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[0].starGapCount").value(5))
                .andExpect(jsonPath("$.activities[0].starUnsortedCount").value(0));
        // 경험 찾기엔 싣지 않는다
        mvc.perform(get("/api/experiences")).andExpect(jsonPath("$.experiences[0].star").doesNotExist());
    }

    @Test
    void 정리_오류() throws Exception {
        long empty = activities.save(new Activity(TestLogin.USER_ID, "이름만 있는 활동", new Period(null, null, false))).getId();
        sort(empty).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_EXPERIENCES"));
        TestLogin.mvc(context, 999999L).perform(post("/api/activities/{id}/star", app))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));

        // 정리 중에 또 누르면 409
        starReply = body -> new Reply(200, contractReply(), 500);
        sort().andExpect(status().isAccepted());
        sort().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STAR_ANALYZING"));
        awaitNotAnalyzing();
        assertThat(usage()).isZero();

        // 분석 한도를 다 써도 다시 시도할 수 있다 — 횟수를 세지 않는다(ETC#346)
        jdbc.update("update app_user set daily_analysis_limit = 0 where id = ?", TestLogin.USER_ID);
        sort().andExpect(status().isAccepted());
        assertThat(JsonPath.<String>read(awaitNotAnalyzing(), "$.experiences[0].star.status")).isEqualTo("READY");
    }

    @Test
    void AI_가_실패하면_FAILED_이고_앞_칸은_남는다() throws Exception {
        sort();
        awaitNotAnalyzing();

        starReply = body -> new Reply(502, "{\"error\":{\"code\":\"LLM_ERROR\",\"message\":\"x\"}}", 0);
        sort().andExpect(status().isAccepted());
        String failed = awaitNotAnalyzing();
        assertThat(JsonPath.<String>read(failed, "$.experiences[0].star.status")).isEqualTo("FAILED");
        assertThat((List<?>) JsonPath.read(failed, "$.experiences[0].star.slots")).hasSize(4);

        // 응답 전체 위반(경험 빠짐)도 실패 — 처음부터 실패면 칸이 없다
        long other = activities.save(new Activity(TestLogin.USER_ID, "스터디", new Period(null, null, false))).getId();
        Activity study = activities.findById(other).orElseThrow();
        experiences.save(new Experience(study, "발표했다", List.of(), CompetenciesBy.USER, Actor.ME,
                SourceStatus.USER_ENTERED, 0));
        starReply = body -> new Reply(200, "{\"experiences\":[],\"model\":\"test-model\"}", 0);
        sort(other).andExpect(status().isAccepted());
        String missing = awaitNotAnalyzing(other);
        assertThat(JsonPath.<String>read(missing, "$.experiences[0].star.status")).isEqualTo("FAILED");
        assertThat((List<?>) JsonPath.read(missing, "$.experiences[0].star.slots")).isEmpty();
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[?(@.id == " + other + ")].starUnsortedCount").value(1));
    }

    @Test
    void 서버가_다시_켜지면_정리_중은_실패가_된다() throws Exception {
        jdbc.update("update experience set star_status = 'ANALYZING' where id = ?", chat);
        cleaner.run(null);
        mvc.perform(get("/api/activities/{id}", app)).andExpect(jsonPath("$.experiences[0].star.status").value("FAILED"));
    }

    @Test
    void 빈칸에_답하면_보완이_생기고_칸이_찬다() throws Exception {
        // 정리 전엔 칸이 없다
        answer(chat, "T", "제가 맡았어요").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STAR_NOT_READY"));
        sort();
        awaitNotAnalyzing();

        String body = answer(chat, "T", "제가 맡았어요 — 팀원이 리포트한 버그를 받아 고쳤어요").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chat))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.star.status").value("READY"))
                .andExpect(jsonPath("$.star.slots[1].sourceType").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.star.slots[1].text").value("제가 맡았어요 — 팀원이 리포트한 버그를 받아 고쳤어요"))
                .andExpect(jsonPath("$.star.slots[1].question").value(nullValue()))
                .andExpect(jsonPath("$.supplements.length()").value(3))
                .andExpect(jsonPath("$.supplements[2].question").value("이 문제를 고치는 일은 누가 맡았나요?"))
                .andExpect(jsonPath("$.supplements[2].questionId").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.star.slots[1].sourceId")).longValue())
                .isEqualTo(((Number) JsonPath.read(body, "$.supplements[2].id")).longValue());
        // 질문 없는 칸은 칸 이름이 질문이 된다
        answer(deploy, "S", "학교 축제 기간이었다").andExpect(status().isOk())
                .andExpect(jsonPath("$.supplements[0].question").value("상황"));

        answer(chat, "T", "또").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STAR_SLOT_FILLED"));
        answer(chat, "X", "칸 밖").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        answer(chat, "R", "  ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("EMPTY_TEXT"));
        answer(chat, "R", "가".repeat(1001)).andExpect(status().isBadRequest());
        TestLogin.mvc(context, 999999L).perform(post("/api/experiences/{id}/star/R/answer", chat)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"남의 답\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("EXPERIENCE_NOT_FOUND"));

        // 새로고침해도 남고, 분석 횟수는 쓰지 않는다
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.slots[1].sourceType").value("SUPPLEMENT"));
        assertThat(usage()).isZero();
    }

    @Test
    void 칸_고치기_근거_칸은_새_보완으로_덮고_보완_칸은_그_보완을_고친다() throws Exception {
        sort();
        awaitNotAnalyzing();

        // 근거 칸 — 근거는 그대로, 새 보완(질문 = 칸 이름)이 칸을 덮는다
        edit(chat, "S", "축제 기간에 채팅이 몇 초씩 늦게 왔다").andExpect(status().isOk())
                .andExpect(jsonPath("$.star.slots[0].sourceType").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.star.slots[0].text").value("축제 기간에 채팅이 몇 초씩 늦게 왔다"))
                .andExpect(jsonPath("$.supplements[2].question").value("상황"))
                .andExpect(jsonPath("$.citations[0].quote").value(CHAT_QUOTE))
                .andExpect(jsonPath("$.content").value("채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다"))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"));

        // 보완 칸 — 그 보완의 답을 고친다(새 보완 없음)
        edit(chat, "A", "STOMP 로 구독 방식으로 바꿨다").andExpect(status().isOk())
                .andExpect(jsonPath("$.star.slots[2].sourceId").value(chatSupplement))
                .andExpect(jsonPath("$.star.slots[2].text").value("STOMP 로 구독 방식으로 바꿨다"))
                .andExpect(jsonPath("$.supplements[0].answer").value("STOMP 로 구독 방식으로 바꿨다"))
                .andExpect(jsonPath("$.supplements.length()").value(3))
                .andExpect(jsonPath("$.star.status").value("READY"));

        // 빈 글 — 칸만 비운다(보완은 남고 질문은 그대로)
        edit(chat, "A", " ").andExpect(status().isOk())
                .andExpect(jsonPath("$.star.slots[2].sourceType").value(nullValue()))
                .andExpect(jsonPath("$.supplements.length()").value(3));
        // 빈칸 고치기는 답하기와 같다
        edit(chat, "R", "응답이 빨라졌다는 리뷰를 받았다").andExpect(status().isOk())
                .andExpect(jsonPath("$.star.slots[3].sourceType").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.supplements[3].question").value("바꾼 뒤 무엇이 달라졌나요?"));
        edit(chat, "S", "가".repeat(1001)).andExpect(status().isBadRequest());
        edit(chat, "Q", "칸 밖").andExpect(status().isBadRequest());
    }

    @Test
    void 근거_보완이_바뀌어도_다시_정리하지_않고_칸만_맞춘다() throws Exception {
        // 채팅 T 를 근거로 채운 응답(수행 주체 ME)
        starReply = body -> new Reply(200, contractReply().replace(
                "{\"slot\":\"T\",\"sourceType\":null,\"sourceId\":null,\"text\":null,\"question\":\"이 문제를 고치는 일은 누가 맡았나요?\"}",
                "{\"slot\":\"T\",\"sourceType\":\"CITATION\",\"sourceId\":" + chatCitation
                        + ",\"text\":\"내가 폴링을 WebSocket 으로 바꿔 해결했다\",\"question\":null}"), 0);
        sort();
        awaitNotAnalyzing();
        int calls = STARRED.size();

        // 보완 전부를 가리키는 칸 — 보완을 고치면 칸이 따라가고 READY 그대로
        patchSupplement(chatSupplement, "WebSocket 으로 바꿨다");
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[2].text").value("WebSocket 으로 바꿨다"));

        // 보완 지우기 — 그 칸은 빈칸, 경험은 READY 그대로
        mvc.perform(delete("/api/supplements/{id}", chatSupplement)).andExpect(status().isNoContent());
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[2].sourceType").value(nullValue()));
        mvc.perform(get("/api/activities"))
                .andExpect(jsonPath("$.activities[0].starUnsortedCount").value(0))
                .andExpect(jsonPath("$.activities[0].starGapCount").value(5));

        // 수행 주체가 ME 에서 바뀌면 근거로 찬 T 칸만 빈칸 — S(근거)는 그대로, 상태도 그대로
        mvc.perform(patch("/api/experiences/{id}", chat).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":\"TEAM\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[0].sourceType").value("CITATION"))
                .andExpect(jsonPath("$.experiences[0].star.slots[1].sourceType").value(nullValue()));

        // 알아서 정리도 READY 경험은 건드리지 않는다 — AI 를 다시 부르지 않았다
        assertThat(sweeper.sweep(OffsetDateTime.now().plusSeconds(1))).isZero();
        assertThat(STARRED).hasSize(calls);
    }

    @Test
    void 보완_일부를_가리키던_칸은_그_글이_남으면_그대로_사라지면_빈칸() throws Exception {
        starReply = body -> new Reply(200, contractReply().replace(
                "\"text\":\"서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다\"",
                "\"text\":\"서버에 STOMP 엔드포인트를 만들고\""), 0);
        sort();
        awaitNotAnalyzing();
        patchSupplement(chatSupplement, "서버에 STOMP 엔드포인트를 만들고, 앱은 그대로 뒀다");
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[2].text").value("서버에 STOMP 엔드포인트를 만들고"));
        patchSupplement(chatSupplement, "앱만 구독 방식으로 바꿨다");
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[2].sourceType").value(nullValue()));
    }

    @Test
    void 경험을_옮기면_칸이_따라가고_합치면_남길_경험의_칸이_남는다() throws Exception {
        sort();
        awaitNotAnalyzing();
        long other = activities.save(new Activity(TestLogin.USER_ID, "다른 활동", new Period(null, null, false))).getId();
        mvc.perform(post("/api/experiences/{id}/move", chat).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toActivityId\":" + other + "}")).andExpect(status().isOk());
        mvc.perform(get("/api/activities/{id}", other))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[0].sourceId").value(chatCitation));
        mvc.perform(post("/api/experiences/{id}/move", chat).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toActivityId\":" + app + "}")).andExpect(status().isOk());

        mvc.perform(post("/api/experiences/{id}/merge", chat).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromExperienceId\":" + deploy + "}")).andExpect(status().isOk());
        mvc.perform(get("/api/activities/{id}", app))
                .andExpect(jsonPath("$.experiences.length()").value(1))
                .andExpect(jsonPath("$.experiences[0].star.status").value("READY"))
                .andExpect(jsonPath("$.experiences[0].star.slots[0].sourceId").value(chatCitation));
        assertThat(jdbc.queryForObject("select count(*) from experience_star_slot where experience_id = ?",
                Integer.class, deploy)).isZero();

        // 경험을 지우면 칸도 같이 지워진다
        mvc.perform(delete("/api/experiences/{id}", chat)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from experience_star_slot", Integer.class)).isZero();
    }

    @Test
    void 새_자료_분석이_만든_경험은_분석_뒤에_횟수_없이_정리된다() throws Exception {
        extractReply = """
                {"activities":[{"name":"알고리즘 스터디","period":null,"experiences":[
                  {"content":"문제 선정을 맡았다","competencies":[],"actor":"ME","sourceStatus":"SOURCE_CONFIRMED",
                   "citations":[{"quote":"문제 선정을 맡는다","matched":true,"location":{"startLine":1,"endLine":1}}]}]}],
                 "model":"test-model"}""";
        starReply = body -> {
            long id = ((Number) JsonPath.read(body, "$.experiences[0].id")).longValue();
            long citation = ((Number) JsonPath.read(body, "$.experiences[0].citations[0].id")).longValue();
            return new Reply(200, """
                    {"experiences":[{"id":%d,"slots":[
                      {"slot":"S","sourceType":null,"sourceId":null,"text":null,"question":"어떤 스터디였나요?"},
                      {"slot":"T","sourceType":"CITATION","sourceId":%d,"text":"문제 선정을 맡는다","question":null},
                      {"slot":"A","sourceType":null,"sourceId":null,"text":null,"question":null},
                      {"slot":"R","sourceType":null,"sourceId":null,"text":null,"question":null}]}],"model":"test-model"}"""
                    .formatted(id, citation), 0);
        };
        String submitted = mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"text\":\"문제 선정을 맡는다\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long jobId = ((Number) JsonPath.read(submitted, "$.jobId")).longValue();
        String job = awaitJob(jobId);
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("COMPLETED");
        long study = ((Number) JsonPath.<List<Number>>read(job, "$.activityIds").getFirst()).longValue();

        String done = awaitNotAnalyzing(study);
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.status")).isEqualTo("READY");
        assertThat(JsonPath.<String>read(done, "$.experiences[0].star.slots[1].text")).isEqualTo("문제 선정을 맡는다");
        // 자료 넣기 1회만 센다
        assertThat(usage()).isEqualTo(1);
    }

    @Test
    void 새_자료_분석_뒤_정리가_실패해도_분석은_성공이다() throws Exception {
        extractReply = """
                {"activities":[{"name":"동아리","period":null,"experiences":[
                  {"content":"회의록을 썼다","competencies":[],"actor":"ME","sourceStatus":"NEEDS_REVIEW","citations":[]}]}],
                 "model":"test-model"}""";
        starReply = body -> new Reply(504, "{\"error\":{\"code\":\"LLM_TIMEOUT\",\"message\":\"x\"}}", 0);
        String submitted = mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"text\":\"회의록\"}"))
                .andReturn().getResponse().getContentAsString();
        String job = awaitJob(((Number) JsonPath.read(submitted, "$.jobId")).longValue());
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("COMPLETED");
        long club = ((Number) JsonPath.<List<Number>>read(job, "$.activityIds").getFirst()).longValue();
        assertThat(JsonPath.<String>read(awaitNotAnalyzing(club), "$.experiences[0].star.status")).isEqualTo("FAILED");
    }

    @Test
    void 정리_안_한_경험은_알아서_정리된다_횟수_없이_새_경험만() throws Exception {
        starReply = body -> new Reply(200, emptyReply(body), 0);
        // 막 생긴 경험은 조금 둔다 — before 보다 늦게 생겼으면 맡기지 않는다
        assertThat(sweeper.sweep(OffsetDateTime.now().minusMinutes(1))).isZero();

        assertThat(sweeper.sweep(OffsetDateTime.now().plusSeconds(1))).isEqualTo(1);
        String done = awaitNotAnalyzing();
        assertThat(JsonPath.<List<String>>read(done, "$.experiences[*].star.status")).containsExactly("READY", "READY");
        assertThat(usage()).isZero();

        // 나중에 직접 기록한 경험 — 그 경험만 보낸다(이미 정리한 칸은 그대로)
        Activity activity = activities.findById(app).orElseThrow();
        long added = experiences.save(new Experience(activity, "발표 자료를 만들었다", List.of(), CompetenciesBy.USER,
                Actor.ME, SourceStatus.USER_ENTERED, 2)).getId();
        assertThat(sweeper.sweep(OffsetDateTime.now().plusSeconds(1))).isEqualTo(1);
        awaitNotAnalyzing();
        assertThat(JsonPath.<List<Number>>read(STARRED.getLast(), "$.experiences[*].id")).extracting(Number::longValue)
                .containsExactly(added);
        assertThat(sweeper.sweep(OffsetDateTime.now().plusSeconds(1))).isZero();
    }

    @Test
    void 알아서_정리는_동시에_둘까지만_부른다() throws Exception {
        for (int i = 0; i < 5; i++) {
            Activity a = activities.save(new Activity(TestLogin.USER_ID, "활동 " + i, new Period(null, null, false)));
            experiences.save(new Experience(a, "한 일 " + i, List.of(), CompetenciesBy.USER, Actor.ME,
                    SourceStatus.USER_ENTERED, 0));
        }
        starReply = body -> new Reply(200, emptyReply(body), 200);
        long deadline = System.currentTimeMillis() + 15_000;
        while (jdbc.queryForObject("select count(*) from experience where star_status <> 'READY'", Integer.class) > 0) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            sweeper.sweep(OffsetDateTime.now().plusSeconds(1));
            Thread.sleep(20);
        }
        assertThat(STARRED).hasSize(6);
        assertThat(MAX_IN_FLIGHT.get()).isEqualTo(StarRunner.MAX_CONCURRENT);
    }

    // 받은 경험마다 네 칸 모두 빈칸
    private static String emptyReply(String body) {
        String slots = """
                [{"slot":"S","sourceType":null,"sourceId":null,"text":null,"question":null},
                 {"slot":"T","sourceType":null,"sourceId":null,"text":null,"question":null},
                 {"slot":"A","sourceType":null,"sourceId":null,"text":null,"question":null},
                 {"slot":"R","sourceType":null,"sourceId":null,"text":null,"question":null}]""";
        List<String> items = JsonPath.<List<Number>>read(body, "$.experiences[*].id").stream()
                .map(id -> "{\"id\":" + id.longValue() + ",\"slots\":" + slots + "}").toList();
        return "{\"experiences\":[" + String.join(",", items) + "],\"model\":\"test-model\"}";
    }

    private ResultActions sort() throws Exception {
        return sort(app);
    }

    private ResultActions sort(long activityId) throws Exception {
        return mvc.perform(post("/api/activities/{id}/star", activityId));
    }

    private ResultActions answer(long experienceId, String slot, String answer) throws Exception {
        return mvc.perform(post("/api/experiences/{id}/star/{slot}/answer", experienceId, slot)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonPath.parse(java.util.Map.of("answer", answer)).jsonString()));
    }

    private ResultActions edit(long experienceId, String slot, String text) throws Exception {
        return mvc.perform(put("/api/experiences/{id}/star/{slot}", experienceId, slot)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonPath.parse(java.util.Map.of("text", text)).jsonString()));
    }

    private void patchSupplement(long supplementId, String answer) throws Exception {
        mvc.perform(patch("/api/supplements/{id}", supplementId).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonPath.parse(java.util.Map.of("answer", answer)).jsonString()))
                .andExpect(status().isOk());
    }

    private int usage() {
        return jdbc.queryForObject("select coalesce(sum(count), 0) from analysis_usage where user_id = ?", Integer.class,
                TestLogin.USER_ID);
    }

    private String awaitNotAnalyzing() throws Exception {
        return awaitNotAnalyzing(app);
    }

    private String awaitNotAnalyzing(long activityId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Integer analyzing = jdbc.queryForObject(
                    "select count(*) from experience where star_status = 'ANALYZING'", Integer.class);
            if (analyzing == 0) {
                if (activities.existsById(activityId)) {
                    return mvc.perform(get("/api/activities/{id}", activityId)).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                }
                return null;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("STAR 정리가 15초 안에 끝나지 않았습니다");
    }

    private String awaitJob(long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/jobs/{id}", jobId)).andReturn().getResponse().getContentAsString();
            String status = JsonPath.read(body, "$.status");
            if (!"ANALYZING".equals(status) && !"QUEUED".equals(status)) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("작업 " + jobId + " 이(가) 15초 안에 끝나지 않았습니다");
    }
}

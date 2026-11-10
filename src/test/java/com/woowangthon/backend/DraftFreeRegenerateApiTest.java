package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 응답의 draft.freeRegenerate 가 다시 만들기 시작(start)의 분석 횟수 판단과 같은지(SPEC S-77).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 초안이 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=2")
class DraftFreeRegenerateApiTest {

    record FakeReply(int status, String body, long delayMillis) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile FakeReply matchReply;
    static volatile FakeReply draftReply;
    static volatile FakeReply groundReply;

    static {
        try {
            FAKE_AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FAKE_AI.setExecutor(Executors.newCachedThreadPool());
        FAKE_AI.createContext("/match", exchange -> answer(exchange, matchReply));
        FAKE_AI.createContext("/draft", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            answer(exchange, draftReply);
        });
        FAKE_AI.createContext("/ground-check", exchange -> {
            exchange.getRequestBody().readAllBytes();
            answer(exchange, groundReply);
        });
        FAKE_AI.start();
    }

    private static void answer(HttpExchange exchange, FakeReply r) throws IOException {
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
            // BE 가 먼저 끊은 경우
        }
        exchange.close();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String QUOTE = "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.";
    static final String ANSWER = "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다";
    static final String ASK = "WebSocket 으로 바꾼 뒤 메시지가 제때 오는지 어떻게 확인했나요?";
    static final String SUPPLEMENT_QUESTION = "폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SourceRepository sources;

    @Autowired
    ActivityRepository activities;

    @Autowired
    ExperienceRepository experiences;

    @Autowired
    CitationRepository citations;

    @Autowired
    SupplementRepository supplements;

    MockMvc mvc;
    Source source;

    long actA;
    long actB;
    long chat;      // 활동 A · 고르는 경험 · UNKNOWN
    long study;     // 활동 A · 보조 재료(같은 활동) · UNKNOWN
    long b1;        // 활동 B · 다른 활동 · UNKNOWN
    long chatCitation;
    long chatSupplement;
    long questionId;
    int position = 10;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        source = sources.save(new Source(TestLogin.USER_ID, SourceKind.FILE, "바꿔요-회고.md", "회고"));
        actA = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false))).getId();
        actB = activities.save(new Activity(TestLogin.USER_ID, "알고리즘 스터디", new Period("2025-07", "2025-09", false))).getId();
        Experience e101 = experiences.save(new Experience(activities.findById(actA).orElseThrow(),
                "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다", List.of(Competency.PROBLEM_SOLVING),
                CompetenciesBy.AI, Actor.UNKNOWN, SourceStatus.SOURCE_CONFIRMED, 0));
        chat = e101.getId();
        study = saveExperience(actA, "스터디원 6명의 풀이를 매주 리뷰했다", 1);
        b1 = saveExperience(actB, "B 활동의 경험", 1000);
        chatCitation = citations.save(new Citation(e101, source, QUOTE, true, 5, 5)).getId();
        chatSupplement = supplements.save(new Supplement(e101, null, null, SUPPLEMENT_QUESTION, ANSWER)).getId();

        matchReply = new FakeReply(200, """
                {
                  "requirements": [{ "text": "기술 문제 해결", "status": "SUPPORTED", "experienceIds": [%d] }],
                  "recommendations": [
                    { "experienceId": %d, "reason": "이유", "emphasis": "강조", "evidence": [{ "type": "CITATION", "id": %d }], "gaps": [] },
                    { "experienceId": %d, "reason": "이유", "emphasis": "강조", "evidence": [], "gaps": [] },
                    { "experienceId": %d, "reason": "이유", "emphasis": "강조", "evidence": [], "gaps": [] }
                  ],
                  "clarifications": [],
                  "model": "test-model"
                }
                """.formatted(chat, chat, chatCitation, study, b1), 0);
        draftReply = new FakeReply(200, threeSentences(), 0);

        String created = mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization\": \"OO 백엔드 부트캠프\", \"text\": \"기술적인 문제를 해결한 경험을 적어 주세요.\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        questionId = ((Number) JsonPath.read(created, "$.questionId")).longValue();
        awaitQuestion();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table mcp_change");
        jdbc.execute("truncate table guideline");
        jdbc.execute("truncate table new_fact_candidate, answer_dismissed, answer, answer_draft, draft_sentence_evidence, draft_sentence, draft_omitted, draft_guideline, draft_experience, draft, "
                + "grounding_sentence_evidence, grounding_sentence, grounding_check, "
                + "question_recommendation_evidence, question_recommendation, question_requirement, supplement, clarification, "
                + "question, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity_link, activity, source_section, source");
    }

    // --- freeRegenerate 와 실제 횟수 ---

    @Test
    void 막_완료된_초안은_freeRegenerate_가_false_이고_다시_만들면_센다() throws Exception {
        int base = used();
        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();
        assertThat(free(done)).isFalse();

        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(used()).isEqualTo(base + 2);
    }

    @Test
    void 빠진_사실에_답하면_true_이고_다시_만들어도_세지_않으며_새_초안은_false_다() throws Exception {
        draftReply = new FakeReply(200, reply(sentence("FACT", "문장", "CITATION", chatCitation),
                "{\"text\": \"확인 방법\", \"question\": \"%s\", \"experienceId\": %d}".formatted(ASK, chat)), 0);
        int base = used();
        start(chat).andExpect(status().isAccepted());
        String first = awaitDraft();
        assertThat(free(first)).isFalse();
        long asked = ((Number) JsonPath.read(first, "$.draft.omitted[0].clarificationId")).longValue();

        mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\": \"두 기기로 확인했다\"}")).andExpect(status().isCreated());
        assertThat(free(getQuestion())).isTrue();

        start(chat).andExpect(status().isAccepted());
        String again = awaitDraft();
        assertThat(used()).isEqualTo(base + 1);
        // 새 초안은 아직 답한 게 없다
        assertThat(free(again)).isFalse();
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(used()).isEqualTo(base + 2);
    }

    @Test
    void 초안을_시작한_뒤_고른_경험의_주체를_정하면_true_이고_다시_만들어도_세지_않는다() throws Exception {
        int base = used();
        start(chat).andExpect(status().isAccepted());
        assertThat(free(awaitDraft())).isFalse();

        decide(actA, "ME").andExpect(status().isOk());
        assertThat(free(getQuestion())).isTrue();

        start(chat).andExpect(status().isAccepted());
        String again = awaitDraft();
        assertThat(used()).isEqualTo(base + 1);
        assertThat(free(again)).isFalse();
    }

    @Test
    void 초안을_시작하기_전에_주체를_정했으면_false_이고_다시_만들면_센다() throws Exception {
        decide(actA, "ME").andExpect(status().isOk());
        int base = used();
        start(chat).andExpect(status().isAccepted());
        assertThat(free(awaitDraft())).isFalse();

        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(used()).isEqualTo(base + 2);
    }

    @Test
    void useContext_를_켜면_같은_활동의_보조_경험_주체를_정해도_true_다() throws Exception {
        int base = used();
        start(chat).andExpect(status().isAccepted());
        assertThat(free(awaitDraft())).isFalse();

        patchExperience(study, "{\"actor\": \"ME\"}").andExpect(status().isOk());
        assertThat(free(getQuestion())).isTrue();

        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(used()).isEqualTo(base + 1);
    }

    @Test
    void useContext_를_끄면_보조_경험_주체를_정해도_false_이고_센다() throws Exception {
        int base = used();
        postJson("{\"experienceIds\": [%d], \"useContext\": false}".formatted(chat)).andExpect(status().isAccepted());
        assertThat(free(awaitDraft())).isFalse();

        patchExperience(study, "{\"actor\": \"ME\"}").andExpect(status().isOk());
        assertThat(free(getQuestion())).isFalse();

        postJson("{\"experienceIds\": [%d], \"useContext\": false}".formatted(chat)).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(used()).isEqualTo(base + 2);
    }

    @Test
    void 다른_활동_경험의_주체를_정해도_false_이고_센다() throws Exception {
        int base = used();
        start(chat).andExpect(status().isAccepted());
        awaitDraft();

        decide(actB, "ME").andExpect(status().isOk());
        assertThat(free(getQuestion())).isFalse();

        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(used()).isEqualTo(base + 2);
    }

    @Test
    void 만드는_중에는_주체를_정했어도_false_이고_끝나면_true_다() throws Exception {
        draftReply = new FakeReply(200, threeSentences(), 800);
        int base = used();
        start(chat).andExpect(status().isAccepted());
        String analyzing = getQuestion();
        assertThat((String) JsonPath.read(analyzing, "$.draft.status")).isEqualTo("ANALYZING");
        assertThat(free(analyzing)).isFalse();

        decide(actA, "ME").andExpect(status().isOk());
        String still = getQuestion();
        // 아직 만드는 중이면 false, 이미 끝났다면 주체를 정한 시각이 초안 시작 뒤라 true
        boolean analyzingNow = "ANALYZING".equals(JsonPath.read(still, "$.draft.status"));
        if (analyzingNow) {
            assertThat(free(still)).isFalse();
        }
        String done = awaitDraft();
        assertThat(free(done)).isTrue();
        assertThat(used()).isEqualTo(base + 1);
    }

    @Test
    void 실패한_초안은_답한_상태가_없어_false_이고_시작_뒤_주체를_정하면_true_다() throws Exception {
        draftReply = new FakeReply(500, "{\"error\": \"x\"}", 0);
        int base = used();
        start(chat).andExpect(status().isAccepted());
        String failed = awaitDraft();
        assertThat((String) JsonPath.read(failed, "$.draft.status")).isEqualTo("FAILED");
        assertThat(free(failed)).isFalse();
        assertThat(used()).isEqualTo(base + 1);

        // 실패한 초안을 다시 시도하면 센다
        start(chat).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("FAILED");
        assertThat(used()).isEqualTo(base + 2);

        decide(actA, "ME").andExpect(status().isOk());
        String after = getQuestion();
        assertThat((String) JsonPath.read(after, "$.draft.status")).isEqualTo("FAILED");
        assertThat(free(after)).isTrue();

        draftReply = new FakeReply(200, threeSentences(), 0);
        start(chat).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");
        assertThat(used()).isEqualTo(base + 2);
    }

    // --- 도우미 ---

    private static boolean free(String json) {
        return JsonPath.read(json, "$.draft.freeRegenerate");
    }

    // --- 도우미 ---

    private long saveExperience(long activityId, String content, int minutesAgo) {
        Experience e = experiences.save(new Experience(activities.findById(activityId).orElseThrow(), content,
                List.of(Competency.LEARNING), CompetenciesBy.AI, Actor.UNKNOWN, SourceStatus.SOURCE_CONFIRMED, position++));
        jdbc.update("update experience set created_at = now() - make_interval(mins => ?) where id = ?", minutesAgo, e.getId());
        return e.getId();
    }

    private String threeSentences() {
        return reply(sentence("FACT", "첫째 문장", "CITATION", chatCitation) + ",\n"
                + sentence("FACT", "둘째 문장", "SUPPLEMENT", chatSupplement) + ",\n"
                + "{\"kind\": \"OPINION\", \"text\": \"포부\", \"evidence\": []}", "");
    }

    private static String sentence(String kind, String text, String evidenceType, long evidenceId) {
        return "{\"kind\": \"%s\", \"text\": \"%s\", \"evidence\": [{\"type\": \"%s\", \"id\": %d}]}"
                .formatted(kind, text, evidenceType, evidenceId);
    }

    // sentences · omitted 쪽 JSON 조각으로 응답 본문을 만든다
    private static String reply(String sentences, String omitted) {
        return "{\"sentences\": [" + sentences + "], \"omitted\": [" + omitted + "]}";
    }

    private List<Long> sentenceIds(String json) {
        List<Number> found = JsonPath.read(json, "$.draft.sentences[*].id");
        return found.stream().map(Number::longValue).toList();
    }

    private int used() {
        return jdbc.queryForObject("select count from analysis_usage where user_id = ?", Integer.class, TestLogin.USER_ID);
    }

    private String actorOf(long experienceId) {
        return jdbc.queryForObject("select actor from experience where id = ?", String.class, experienceId);
    }

    private Object actorDecidedAt(long experienceId) {
        return jdbc.queryForObject("select actor_decided_at from experience where id = ?", java.sql.Timestamp.class, experienceId);
    }

    private ResultActions confirm(long sentenceId) throws Exception {
        return mvc.perform(post("/api/draft-sentences/{id}/confirm", sentenceId));
    }

    private ResultActions decide(long activityId, String actor) throws Exception {
        return mvc.perform(post("/api/activities/{id}/actor", activityId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":\"" + actor + "\"}"));
    }

    private ResultActions patchExperience(long id, String json) throws Exception {
        return mvc.perform(patch("/api/experiences/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions start(long... ids) throws Exception {
        return postJson("{\"experienceIds\": " + java.util.Arrays.toString(ids) + "}");
    }

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/questions/{id}/draft", questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void startGrounding() throws Exception {
        mvc.perform(post("/api/questions/{id}/grounding-check", questionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"문장\"}")).andExpect(status().isAccepted());
    }

    private String getQuestion() throws Exception {
        return mvc.perform(get("/api/questions/{id}", questionId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void awaitQuestion() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if ("COMPLETED".equals(JsonPath.read(getQuestion(), "$.status"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("문항 " + questionId + " 이(가) COMPLETED 가 되지 않았습니다");
    }

    // 초안은 다른 스레드에서 돈다 — 끝날 때까지 문항을 조회한다(FE 폴링과 같다)
    private String awaitDraft() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = getQuestion();
            if (!"ANALYZING".equals(JsonPath.read(body, "$.draft.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("문항 " + questionId + " 의 초안이 15초 안에 끝나지 않았습니다");
    }

    private String awaitGrounding() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/questions/{id}/grounding-check", questionId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            if (!"ANALYZING".equals(JsonPath.read(body, "$.groundingCheck.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("문항 " + questionId + " 의 근거 점검이 15초 안에 끝나지 않았습니다");
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 초안 문장 「빼기」 · 「다시 넣기」(SPEC S-79, ETC docs/contracts/question-api.md).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 초안이 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=2")
class DraftRemoveApiTest {

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
                + "question, application_posting_line, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity_link, activity, source_section, source");
    }


    @Test
    void 새_초안_문장은_removed_false_다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((List<Boolean>) JsonPath.read(done, "$.draft.sentences[*].removed")).containsExactly(false, false, false);
    }

    @Test
    void 빼기는_204_이고_그_문장만_true_다_다시_해도_같고_false_로_되돌린다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        List<Long> ids = sentenceIds(awaitDraft());

        remove(ids.get(1), "{\"removed\": true}").andExpect(status().isNoContent());
        String after = getQuestion();
        assertThat((List<Boolean>) JsonPath.read(after, "$.draft.sentences[*].removed")).containsExactly(false, true, false);
        assertThat(sentenceIds(after)).containsExactlyElementsOf(ids);

        remove(ids.get(1), "{\"removed\": true}").andExpect(status().isNoContent());
        assertThat((List<Boolean>) JsonPath.read(getQuestion(), "$.draft.sentences[*].removed")).containsExactly(false, true, false);

        remove(ids.get(1), "{\"removed\": false}").andExpect(status().isNoContent());
        assertThat((List<Boolean>) JsonPath.read(getQuestion(), "$.draft.sentences[*].removed")).containsExactly(false, false, false);
    }

    @Test
    void removed_와_confirmed_는_서로_독립이다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        List<Long> ids = sentenceIds(awaitDraft());
        List<String> texts = JsonPath.read(getQuestion(), "$.draft.sentences[*].text");

        confirm(ids.getFirst()).andExpect(status().isNoContent());
        remove(ids.getFirst(), "{\"removed\": true}").andExpect(status().isNoContent());

        String after = getQuestion();
        assertThat((List<Boolean>) JsonPath.read(after, "$.draft.sentences[*].confirmed")).containsExactly(true, false, false);
        assertThat((List<Boolean>) JsonPath.read(after, "$.draft.sentences[*].removed")).containsExactly(true, false, false);

        // 다시 넣어도 confirmed 는 그대로, 글 · 종류도 그대로
        remove(ids.getFirst(), "{\"removed\": false}").andExpect(status().isNoContent());
        String again = getQuestion();
        assertThat((List<Boolean>) JsonPath.read(again, "$.draft.sentences[*].confirmed")).containsExactly(true, false, false);
        assertThat((List<Boolean>) JsonPath.read(again, "$.draft.sentences[*].removed")).containsExactly(false, false, false);
        assertThat((List<String>) JsonPath.read(again, "$.draft.sentences[*].text")).containsExactlyElementsOf(texts);
        assertThat((List<String>) JsonPath.read(again, "$.draft.sentences[*].kind")).containsExactly("FACT", "FACT", "OPINION");
    }

    @Test
    void 잘못된_본문은_400_INVALID_REQUEST() throws Exception {
        start(chat).andExpect(status().isAccepted());
        long id = sentenceIds(awaitDraft()).getFirst();

        expectError(mvc.perform(put("/api/draft-sentences/{id}/removed", id)), 400, "INVALID_REQUEST");
        for (String json : List.of("{}", "{\"removed\": null}", "{\"removed\": \"true\"}", "{\"removed\": 1}")) {
            expectError(remove(id, json), 400, "INVALID_REQUEST");
        }

        assertThat((List<Boolean>) JsonPath.read(getQuestion(), "$.draft.sentences[*].removed")).containsExactly(false, false, false);
    }

    @Test
    void 없는_문장은_404_DRAFT_SENTENCE_NOT_FOUND() throws Exception {
        start(chat).andExpect(status().isAccepted());
        awaitDraft();

        expectError(remove(999_999, "{\"removed\": true}"), 404, "DRAFT_SENTENCE_NOT_FOUND");
    }

    @Test
    void 남의_문장은_404_이고_바뀌지_않는다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        long mine = sentenceIds(awaitDraft()).getFirst();

        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            expectError(TestLogin.mvc(context, other).perform(put("/api/draft-sentences/{id}/removed", mine)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"removed\": true}")),
                    404, "DRAFT_SENTENCE_NOT_FOUND");
        } finally {
            jdbc.update("delete from app_user where id = ?", other);
        }

        assertThat((List<Boolean>) JsonPath.read(getQuestion(), "$.draft.sentences[*].removed")).containsExactly(false, false, false);
        assertThat(jdbc.queryForObject("select count(*) from draft_sentence where removed", Integer.class)).isZero();
    }

    @Test
    void 다시_만들면_옛_문장은_404_이고_새_문장은_removed_false_다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        List<Long> oldIds = sentenceIds(awaitDraft());
        remove(oldIds.getFirst(), "{\"removed\": true}").andExpect(status().isNoContent());

        start(chat).andExpect(status().isAccepted());
        String again = awaitDraft();

        List<Long> newIds = sentenceIds(again);
        assertThat(newIds).hasSize(3).doesNotContainAnyElementsOf(oldIds);
        assertThat((List<Boolean>) JsonPath.read(again, "$.draft.sentences[*].removed")).containsExactly(false, false, false);
        for (long old : oldIds) {
            expectError(remove(old, "{\"removed\": true}"), 404, "DRAFT_SENTENCE_NOT_FOUND");
        }
        remove(newIds.getFirst(), "{\"removed\": true}").andExpect(status().isNoContent());
    }

    @Test
    void 빼기는_분석_횟수를_세지_않는다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        List<Long> ids = sentenceIds(awaitDraft());
        int before = used();
        jdbc.update("update app_user set daily_analysis_limit = ? where id = ?", before, TestLogin.USER_ID); // 한도를 다 써도 된다

        for (long id : ids) {
            remove(id, "{\"removed\": true}").andExpect(status().isNoContent());
        }
        remove(ids.getFirst(), "{\"removed\": false}").andExpect(status().isNoContent());

        assertThat(used()).isEqualTo(before);
        assertThat((List<Boolean>) JsonPath.read(getQuestion(), "$.draft.sentences[*].removed")).containsExactly(false, true, true);
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

    private ResultActions remove(long sentenceId, String json) throws Exception {
        return mvc.perform(put("/api/draft-sentences/{id}/removed", sentenceId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions confirm(long sentenceId) throws Exception {
        return mvc.perform(post("/api/draft-sentences/{id}/confirm", sentenceId));
    }

    private ResultActions start(long... ids) throws Exception {
        return postJson("{\"experienceIds\": " + java.util.Arrays.toString(ids) + "}");
    }

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/questions/{id}/draft", questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
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

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

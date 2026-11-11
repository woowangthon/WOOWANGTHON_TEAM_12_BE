package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import com.woowangthon.backend.mcp.McpWriteService;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 초안 재료 · 목표 글자 수 · AI 보탬 · 문장 속 빈칸(SPEC S-75 · S-77 · S-78, ETC docs/contracts/question-api.md · ai-api.md).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 초안이 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=2")
class DraftMaterialApiTest {

    record FakeReply(int status, String body, long delayMillis) {
    }

    static final HttpServer FAKE_AI;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile FakeReply matchReply;
    static volatile FakeReply draftReply;

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
    static final String SOURCE_NAME = "바꿔요-회고.md";

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

    @Autowired
    McpWriteService mcpWrites;

    MockMvc mvc;
    Source source;

    long actA;      // 첫째 활동
    long actB;
    long actC;
    long chat;      // 활동 A · 원본에서 찾은 근거 + 못 찾은 근거 + 보완
    long study;     // 활동 A · USER_ENTERED
    long b1;        // 활동 B
    long c1;        // 활동 C
    long chatCitation;
    long chatSupplement;
    long questionId;
    int position = 10;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        source = sources.save(new Source(TestLogin.USER_ID, SourceKind.FILE, SOURCE_NAME, "회고"));
        actA = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false))).getId();
        actB = activities.save(new Activity(TestLogin.USER_ID, "알고리즘 스터디", new Period("2025-07", "2025-09", false))).getId();
        actC = activities.save(new Activity(TestLogin.USER_ID, "해커톤", new Period("2025-10", "2025-10", false))).getId();
        Experience e101 = experiences.save(new Experience(activities.findById(actA).orElseThrow(),
                "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다", List.of(Competency.PROBLEM_SOLVING),
                CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0));
        Experience e106 = experiences.save(new Experience(activities.findById(actA).orElseThrow(),
                "스터디원 6명의 풀이를 매주 리뷰했다", List.of(Competency.LEARNING), CompetenciesBy.USER, Actor.ME,
                SourceStatus.USER_ENTERED, 1));
        chat = e101.getId();
        study = e106.getId();
        b1 = saveExperience(actB, "B 활동의 고른 경험", SourceStatus.SOURCE_CONFIRMED, 1000);
        c1 = saveExperience(actC, "C 활동의 고른 경험", SourceStatus.SOURCE_CONFIRMED, 1000);
        setCreated(chat, 1000);
        setCreated(study, 1000);
        chatCitation = citations.save(new Citation(e101, source, QUOTE, true, 5, 5)).getId();
        citations.save(new Citation(e101, source, "원본에서 못 찾은 발췌", false, null, null));
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
                """.formatted(chat, chat, chatCitation, b1, c1), 0);
        draftReply = new FakeReply(200, reply(okSentence(chatCitation), ""), 0);

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

    // --- 보조 재료 고르기(S-75) ---

    @Test
    void 보조_재료는_첫째_활동이_먼저다() throws Exception {
        long a1 = saveExperience(actA, "A 최근", SourceStatus.NEEDS_REVIEW, 10);
        long a2 = saveExperience(actA, "A 그다음", SourceStatus.NEEDS_REVIEW, 20);
        long b2 = saveExperience(actB, "B 최근", SourceStatus.NEEDS_REVIEW, 5);
        long b3 = saveExperience(actB, "B 그다음", SourceStatus.NEEDS_REVIEW, 6);
        long b4 = saveExperience(actB, "B 오래됨", SourceStatus.NEEDS_REVIEW, 7);

        start(chat, b1).andExpect(status().isAccepted());
        awaitDraft();

        // 12개가 안 차니 다 싣는다 — A 활동 (a1, a2, 가장 오래된 study) → B 활동, 활동별로 묶어서. 고른 경험(chat · b1)은 빠진다
        assertThat(contextIds(RECEIVED.getFirst())).containsExactly(a1, a2, study, b2, b3, b4);
        assertThat(ids(RECEIVED.getFirst(), "$.experiences[*].id")).containsExactly(chat, b1);
    }

    @Test
    void 둘째_셋째_활동은_각_2개까지고_나머지_몫은_첫째가_받는다() throws Exception {
        List<Long> a = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            a.add(saveExperience(actA, "A " + i, SourceStatus.NEEDS_REVIEW, i));
        }
        List<Long> b = new ArrayList<>();
        List<Long> c = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            b.add(saveExperience(actB, "B " + i, SourceStatus.NEEDS_REVIEW, 100 + i));
            c.add(saveExperience(actC, "C " + i, SourceStatus.NEEDS_REVIEW, 100 + i));
        }

        start(chat, b1, c1).andExpect(status().isAccepted());
        awaitDraft();

        List<Long> expected = new ArrayList<>(a.subList(0, 8));
        expected.addAll(b.subList(0, 2));
        expected.addAll(c.subList(0, 2));
        assertThat(contextIds(RECEIVED.getFirst())).hasSize(12).containsExactlyElementsOf(expected);
    }

    @Test
    void 첫째_활동에_모자라면_남는_몫이_다음_활동으로_넘어간다() throws Exception {
        // 활동 A 의 후보는 study 하나뿐
        List<Long> b = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            b.add(saveExperience(actB, "B " + i, SourceStatus.NEEDS_REVIEW, i));
        }

        start(chat, b1).andExpect(status().isAccepted());
        awaitDraft();

        List<Long> expected = new ArrayList<>(List.of(study));
        expected.addAll(b.subList(0, 11));
        assertThat(contextIds(RECEIVED.getFirst())).containsExactlyElementsOf(expected);
    }

    @Test
    void 같은_활동을_둘_골라도_한_번만_세고_고른_경험은_빠진다() throws Exception {
        List<Long> a = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            a.add(saveExperience(actA, "A " + i, SourceStatus.NEEDS_REVIEW, i));
        }
        List<Long> b = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            b.add(saveExperience(actB, "B " + i, SourceStatus.NEEDS_REVIEW, i));
        }

        // c1 의 추천 자리를 study 로 바꿔 chat · study · b1 을 고를 수 있게 한다
        jdbc.update("update question_recommendation set experience_id = ? where experience_id = ?", study, c1);
        start(chat, study, b1).andExpect(status().isAccepted());
        awaitDraft();

        // 활동 A 는 첫 자리 한 번만 — 12 - 둘째 활동 2개 = 10개, chat · study 는 후보가 아니다
        List<Long> expected = new ArrayList<>(a.subList(0, 10));
        expected.addAll(b.subList(0, 2));
        List<Long> sent = contextIds(RECEIVED.getFirst());
        assertThat(sent).containsExactlyElementsOf(expected).doesNotContain(chat, study);
    }

    @Test
    void 문항_유형과_역량이_겹치는_경험이_활동_안에서_먼저고_그다음_최근_순이다() throws Exception {
        long teamNew = saveExperience(actA, "협업 최근", SourceStatus.NEEDS_REVIEW, 1, Competency.COLLABORATION);
        long learn = saveExperience(actA, "학습", SourceStatus.NEEDS_REVIEW, 20, Competency.LEARNING);
        long teamOld = saveExperience(actA, "협업 조금 전", SourceStatus.NEEDS_REVIEW, 2, Competency.COMMUNICATION);
        long impl = saveExperience(actA, "구현", SourceStatus.NEEDS_REVIEW, 30, Competency.IMPLEMENTATION);

        // 유형이 없으면 최근 순만
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(contextIds(RECEIVED.getFirst())).containsExactly(teamNew, teamOld, learn, impl, study);

        // JOB_SKILL(IMPLEMENTATION · LEARNING · ANALYSIS)이 겹치는 것이 먼저 — study(LEARNING)는 오래돼도 협업보다 앞이다
        patchQuestion(questionId, "{\"questionType\": \"JOB_SKILL\"}").andExpect(status().isOk());
        RECEIVED.clear();
        jdbc.update("delete from analysis_usage where user_id = ?", TestLogin.USER_ID);
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(contextIds(RECEIVED.getFirst())).containsExactly(learn, impl, study, teamNew, teamOld);

        // 표에 역량이 없는 유형(MOTIVATION)은 최근 순만
        patchQuestion(questionId, "{\"questionType\": \"MOTIVATION\"}").andExpect(status().isOk());
        RECEIVED.clear();
        jdbc.update("delete from analysis_usage where user_id = ?", TestLogin.USER_ID);
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(contextIds(RECEIVED.getFirst())).containsExactly(teamNew, teamOld, learn, impl, study);
    }

    @Test
    void 경험은_8개까지_고를_수_있고_9개는_400() throws Exception {
        // 추천 자리를 8개로 늘린다 — 활동 B · C 에 경험을 더해 둔다
        List<Long> chosen = new ArrayList<>(List.of(chat, study, b1, c1));
        for (int i = 0; i < 4; i++) {
            long id = saveExperience(i % 2 == 0 ? actB : actC, "추천 " + i, SourceStatus.NEEDS_REVIEW, 100 + i);
            chosen.add(id);
        }
        jdbc.update("delete from question_recommendation where question_id = ?", questionId);
        for (int i = 0; i < chosen.size(); i++) {
            jdbc.update("insert into question_recommendation (question_id, position, experience_id, reason, emphasis) "
                    + "values (?, ?, ?, '이유', '강조')", questionId, i, chosen.get(i));
        }

        start(chosen.stream().mapToLong(Long::longValue).toArray()).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(ids(RECEIVED.getFirst(), "$.experiences[*].id")).containsExactlyElementsOf(chosen);

        // 9개
        expectError(start(1, 2, 3, 4, 5, 6, 7, 8, 9), 400, "INVALID_REQUEST");
    }

    @Test
    void 같은_시각에_생긴_경험은_ID_가_큰_쪽이_먼저다() throws Exception {
        long first = saveExperience(actA, "먼저 저장", SourceStatus.NEEDS_REVIEW, 30);
        long second = saveExperience(actA, "나중 저장", SourceStatus.NEEDS_REVIEW, 30);
        jdbc.update("update experience set created_at = (select created_at from experience where id = ?) where id = ?",
                first, second);

        start(chat).andExpect(status().isAccepted());
        awaitDraft();

        assertThat(contextIds(RECEIVED.getFirst())).containsExactly(second, first, study);
    }

    @Test
    void 보조_재료는_고른_경험과_같은_모양이고_못_찾은_근거와_확인_전_답은_뺀다() throws Exception {
        long ctx = saveExperience(actA, "보조 경험", SourceStatus.SOURCE_CONFIRMED, 1);
        Experience e = experiences.findById(ctx).orElseThrow();
        long matched = citations.save(new Citation(e, source, "찾은 발췌", true, 1, 1)).getId();
        citations.save(new Citation(e, source, "못 찾은 발췌", false, null, null));
        citations.save(new Citation(e, source, "아직 모름", null, null, null));
        long confirmed = supplements.save(new Supplement(e, null, null, "확인된 질문", "확인된 답")).getId();
        answeredByExternalAi(ctx);

        start(chat).andExpect(status().isAccepted());
        awaitDraft();

        String sent = RECEIVED.getFirst();
        assertThat(contextIds(sent)).containsExactly(ctx, study);
        Map<String, Object> part = JsonPath.read(sent, "$.contextExperiences[0]");
        assertThat(part.keySet()).contains("id", "activityName", "period", "content", "competencies", "actor",
                "sourceStatus", "citations", "supplements").doesNotContain("reason", "emphasis", "gaps");
        assertThat((String) JsonPath.read(sent, "$.contextExperiences[0].activityName")).isEqualTo("바꿔요 — 캠퍼스 중고거래 앱");
        assertThat((String) JsonPath.read(sent, "$.contextExperiences[0].sourceStatus")).isEqualTo("SOURCE_CONFIRMED");
        assertThat(ids(sent, "$.contextExperiences[0].citations[*].id")).containsExactly(matched);
        assertThat(ids(sent, "$.contextExperiences[0].supplements[*].id")).containsExactly(confirmed);
        assertThat(sent).doesNotContain("확인 전인 답").doesNotContain("못 찾은 발췌");
        // 고른 경험에는 추천 해석이 그대로 있다
        assertThat((String) JsonPath.read(sent, "$.experiences[0].reason")).isEqualTo("이유");
    }

    // --- useContext ---

    @Test
    void useContext_를_빼거나_null_이면_켠_것이고_끄면_보조_재료_칸이_없다() throws Exception {
        saveExperience(actA, "보조", SourceStatus.NEEDS_REVIEW, 1);

        start(chat).andExpect(status().isAccepted());
        String omittedFlag = awaitDraft();
        assertThat((Boolean) JsonPath.read(omittedFlag, "$.draft.useContext")).isTrue();
        assertThat(RECEIVED.getFirst()).contains("contextExperiences");

        RECEIVED.clear();
        postJson("{\"experienceIds\": [%d], \"useContext\": null}".formatted(chat)).andExpect(status().isAccepted());
        assertThat((Boolean) JsonPath.read(awaitDraft(), "$.draft.useContext")).isTrue();
        assertThat(RECEIVED.getFirst()).contains("contextExperiences");

        RECEIVED.clear();
        postJson("{\"experienceIds\": [%d], \"useContext\": false}".formatted(chat)).andExpect(status().isAccepted());
        String off = awaitDraft();
        assertThat((Boolean) JsonPath.read(off, "$.draft.useContext")).isFalse();
        assertThat(RECEIVED.getFirst()).doesNotContain("contextExperiences");

        RECEIVED.clear();
        postJson("{\"experienceIds\": [%d], \"useContext\": true}".formatted(chat)).andExpect(status().isAccepted());
        assertThat((Boolean) JsonPath.read(awaitDraft(), "$.draft.useContext")).isTrue();
        assertThat(RECEIVED.getFirst()).contains("contextExperiences");
    }

    @Test
    void useContext_가_불리언이_아니면_400_이고_세지_않는다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        int before = used();
        int calls = RECEIVED.size();

        for (String bad : List.of("\"true\"", "1", "0", "\"yes\"", "[]", "{}")) {
            expectError(postJson("{\"experienceIds\": [%d], \"useContext\": %s}".formatted(chat, bad)), 400, "INVALID_REQUEST");
        }

        assertThat(used()).isEqualTo(before);
        assertThat(RECEIVED).hasSize(calls);
        mvc.perform(get("/api/questions/{id}", questionId)).andExpect(jsonPath("$.draft.status").value("COMPLETED"));
    }

    // --- 목표 글자 수가 AI 요청에 실린다(S-77) ---

    @Test
    void 문항의_목표_글자_수를_AI_요청에_싣고_없으면_칸이_없다() throws Exception {
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat((Object) JsonPath.read(RECEIVED.getFirst(), "$.question")).isNotNull();
        assertThat(RECEIVED.getFirst()).doesNotContain("targetLength");

        patchQuestion(questionId, "{\"targetLength\": 800}").andExpect(status().isOk());
        RECEIVED.clear();
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(((Number) JsonPath.read(RECEIVED.getFirst(), "$.question.targetLength")).intValue()).isEqualTo(800);

        patchQuestion(questionId, "{\"targetLength\": null}").andExpect(status().isOk());
        RECEIVED.clear();
        start(chat).andExpect(status().isAccepted());
        awaitDraft();
        assertThat(RECEIVED.getFirst()).doesNotContain("targetLength");
    }

    // --- AI 응답: AI 보탬(ELABORATED) ---

    @Test
    void AI_보탬_문장은_note_를_저장하고_다른_종류는_null_이다() throws Exception {
        String note60 = "가".repeat(60);
        draftReply = new FakeReply(200, reply("""
                {"kind": "FACT", "text": "사실", "evidence": [{"type": "CITATION", "id": %d}]},
                {"kind": "ELABORATED", "text": "AI 가 풀어 쓴 문장", "evidence": [{"type": "SUPPLEMENT", "id": %d}], "note": "수치는 자료에 없어요"},
                {"kind": "ELABORATED", "text": "경계 길이", "evidence": [{"type": "CITATION", "id": %d}], "note": "%s"},
                {"kind": "OPINION", "text": "포부", "evidence": [], "note": null}
                """.formatted(chatCitation, chatSupplement, chatCitation, note60), ""), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((List<String>) JsonPath.read(done, "$.draft.sentences[*].kind"))
                .containsExactly("FACT", "ELABORATED", "ELABORATED", "OPINION");
        assertThat((Object) JsonPath.read(done, "$.draft.sentences[0].note")).isNull();
        assertThat((String) JsonPath.read(done, "$.draft.sentences[1].note")).isEqualTo("수치는 자료에 없어요");
        assertThat((String) JsonPath.read(done, "$.draft.sentences[2].note")).isEqualTo(note60);
        assertThat((Object) JsonPath.read(done, "$.draft.sentences[3].note")).isNull();
    }

    @Test
    void AI_보탬_규칙을_어기면_아무것도_저장하지_않고_실패() throws Exception {
        String cit = "[{\"type\": \"CITATION\", \"id\": %d}]".formatted(chatCitation);
        List<String> sentences = List.of(
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": [], \"note\": \"이유\"}",
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": %s}".formatted(cit),
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": %s, \"note\": null}".formatted(cit),
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": %s, \"note\": \"\"}".formatted(cit),
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": %s, \"note\": \"   \"}".formatted(cit),
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": %s, \"note\": \"%s\"}".formatted(cit, "가".repeat(61)),
                "{\"kind\": \"ELABORATED\", \"text\": \"문장\", \"evidence\": %s, \"note\": \"두\\n줄\"}".formatted(cit),
                "{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": %s, \"note\": \"붙이면 안 됨\"}".formatted(cit),
                "{\"kind\": \"OPINION\", \"text\": \"문장\", \"evidence\": [], \"note\": \"붙이면 안 됨\"}");
        for (String sentence : sentences) {
            expectInvalid("{\"sentences\": [" + sentence + "], \"omitted\": []}");
        }
        assertNothingSaved();
    }

    // --- AI 응답: 문장 속 빈칸 ---

    @Test
    void 문장_속_빈칸은_자리_표시와_문장_순번이_응답에_실린다() throws Exception {
        String edge = "[가나다라마바사아자차]"; // 안 10자 — 괄호까지 12자
        draftReply = new FakeReply(200, """
                {"sentences": [
                  {"kind": "FACT", "text": "저는 장애를 [결과] 까지 줄였습니다.", "evidence": [{"type": "CITATION", "id": %d}]},
                  {"kind": "FACT", "text": "또 %s 를 맡았습니다.", "evidence": [{"type": "CITATION", "id": %d}]}
                ], "omitted": [
                  {"text": "줄인 결과", "question": "%s", "experienceId": %d, "placeholder": "[결과]", "sentenceIndex": 0},
                  {"text": "맡은 일", "question": null, "experienceId": null, "placeholder": "%s", "sentenceIndex": 1},
                  {"text": "빈칸만", "question": null, "experienceId": null}
                ]}""".formatted(chatCitation, edge, chatCitation, ASK, chat, edge), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted.length()").value(3))
                .andExpect(jsonPath("$.draft.omitted[0].placeholder").value("[결과]"))
                .andExpect(jsonPath("$.draft.omitted[0].sentenceIndex").value(0))
                .andExpect(jsonPath("$.draft.omitted[0].clarificationId").isNumber())
                .andExpect(jsonPath("$.draft.omitted[1].placeholder").value(edge))
                .andExpect(jsonPath("$.draft.omitted[1].sentenceIndex").value(1))
                .andExpect(jsonPath("$.draft.omitted[1].clarificationId").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[2].placeholder").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[2].sentenceIndex").value(nullValue()));
    }

    @Test
    void 빈칸_규칙을_어기면_아무것도_저장하지_않고_실패() throws Exception {
        String sentence = "{\"kind\": \"FACT\", \"text\": \"결과는 [결과] 입니다.\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}"
                .formatted(chatCitation);
        String twice = "{\"kind\": \"FACT\", \"text\": \"[결과] 그리고 [결과]\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}"
                .formatted(chatCitation);
        String two = "{\"kind\": \"FACT\", \"text\": \"[결과] 그리고 [방법]\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}"
                .formatted(chatCitation);
        List<String> bodies = List.of(
                // 둘 중 하나만 있음
                body(sentence, gap("\"placeholder\": \"[결과]\"")),
                body(sentence, gap("\"sentenceIndex\": 0")),
                // 꼴이 틀림 — 괄호 없음 · 빈 안쪽 · 공백뿐 · 13자 · 겹 대괄호
                body(sentence, gap("\"placeholder\": \"결과\", \"sentenceIndex\": 0")),
                body(sentence, gap("\"placeholder\": \"[]\", \"sentenceIndex\": 0")),
                body(sentence, gap("\"placeholder\": \"[ ]\", \"sentenceIndex\": 0")),
                body(sentence, gap("\"placeholder\": \"[가나다라마바사아자차카]\", \"sentenceIndex\": 0")),
                body(sentence, gap("\"placeholder\": \"[[결과]]\", \"sentenceIndex\": 0")),
                // 순번이 범위 밖
                body(sentence, gap("\"placeholder\": \"[결과]\", \"sentenceIndex\": 1")),
                body(sentence, gap("\"placeholder\": \"[결과]\", \"sentenceIndex\": -1")),
                // 그 문장에 없음 · 두 번 나옴
                body(sentence, gap("\"placeholder\": \"[없음]\", \"sentenceIndex\": 0")),
                body(twice, gap("\"placeholder\": \"[결과]\", \"sentenceIndex\": 0")),
                // 문장의 [..] 가 짝 없음 — omitted 가 아예 없음 · 둘 중 하나만 짝
                body(sentence, ""),
                body(two, gap("\"placeholder\": \"[결과]\", \"sentenceIndex\": 0")),
                // 같은 짝 두 번
                body(sentence, gap("\"placeholder\": \"[결과]\", \"sentenceIndex\": 0") + ","
                        + gap("\"placeholder\": \"[결과]\", \"sentenceIndex\": 0")));
        for (String body : bodies) {
            expectInvalid(body);
        }
        assertNothingSaved();
    }

    // --- AI 응답: AI 가정(SUPPOSED · ADR-014) ---

    @Test
    void AI_가정_문장은_specific_을_저장하고_짝_omitted_에도_되묻기_ID_가_붙는다() throws Exception {
        draftReply = new FakeReply(200, reply("""
                {"kind": "FACT", "text": "사실", "evidence": [{"type": "CITATION", "id": %d}]},
                {"kind": "SUPPOSED", "text": "원인을 확인했습니다.", "evidence": [], "specific": false},
                {"kind": "SUPPOSED", "text": "응답 시간을 3초에서 1초로 줄였습니다.", "evidence": [{"type": "CITATION", "id": %d}], "specific": true},
                {"kind": "SUPPOSED", "text": "배운 점이 있었습니다.", "evidence": [], "specific": false}
                """.formatted(chatCitation, chatCitation), """
                {"text": "확인한 방법", "question": "%s", "experienceId": %d, "placeholder": null, "sentenceIndex": 1},
                {"text": "줄인 결과", "question": "%s", "experienceId": %d, "placeholder": null, "sentenceIndex": 2},
                {"text": "배운 점", "question": null, "experienceId": null, "placeholder": null, "sentenceIndex": 3}
                """.formatted(ASK, chat, ASK, chat)), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((List<String>) JsonPath.read(done, "$.draft.sentences[*].kind"))
                .containsExactly("FACT", "SUPPOSED", "SUPPOSED", "SUPPOSED");
        assertThat((Object) JsonPath.read(done, "$.draft.sentences[0].specific")).isNull();
        assertThat((Boolean) JsonPath.read(done, "$.draft.sentences[1].specific")).isFalse();
        assertThat((Boolean) JsonPath.read(done, "$.draft.sentences[2].specific")).isTrue();
        assertThat((List<?>) JsonPath.read(done, "$.draft.sentences[1].evidence")).isEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.draft.sentences[2].evidence")).hasSize(1);
        // 짝 — 자리 표시는 null · 문장 순번만. 경험이 있으면 되묻기 ID 가 붙고 없으면 null
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted.length()").value(3))
                .andExpect(jsonPath("$.draft.omitted[0].placeholder").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[0].sentenceIndex").value(1))
                .andExpect(jsonPath("$.draft.omitted[0].clarificationId").isNumber())
                .andExpect(jsonPath("$.draft.omitted[1].sentenceIndex").value(2))
                .andExpect(jsonPath("$.draft.omitted[1].clarificationId").isNumber())
                .andExpect(jsonPath("$.draft.omitted[2].sentenceIndex").value(3))
                .andExpect(jsonPath("$.draft.omitted[2].clarificationId").value(nullValue()))
                .andExpect(jsonPath("$.draft.sentences[2].specific").value(true));

        // 짝의 되묻기 ID 로 기존 답 경로에 답하면 그 경험의 보완이 쌓인다
        long asked = ((Number) JsonPath.read(done, "$.draft.omitted[0].clarificationId")).longValue();
        mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\": \"두 기기로 확인했다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.experienceId").value(chat));
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted[0].supplementId").isNumber())
                .andExpect(jsonPath("$.draft.omitted[0].supplement.answer").value("두 기기로 확인했다"));
    }

    @Test
    void omitted_는_10개까지_받는다() throws Exception {
        draftReply = new FakeReply(200, reply(okSentence(chatCitation),
                String.join(",", java.util.Collections.nCopies(10, gap("\"placeholder\": null, \"sentenceIndex\": null")))), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.draft.omitted")).hasSize(10);
    }

    @Test
    void 옛_초안의_문장은_specific_이_null_이다() throws Exception {
        draftReply = new FakeReply(200, reply(okSentence(chatCitation), ""), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((Object) JsonPath.read(done, "$.draft.sentences[0].specific")).isNull();
    }

    @Test
    void AI_가정_규칙을_어기면_아무것도_저장하지_않고_실패() throws Exception {
        String fact = okSentence(chatCitation);
        String supposed = "{\"kind\": \"SUPPOSED\", \"text\": \"가정\", \"evidence\": [], \"specific\": false}";
        String pair = "{\"text\": \"장면\", \"question\": null, \"experienceId\": null, \"placeholder\": null, \"sentenceIndex\": %d}";
        List<String> bodies = List.of(
                // specific 이 없음 · 불리언이 아님 · SUPPOSED 가 아닌데 있음
                body(fact + "," + supposed.replace(", \"specific\": false", ""), pair.formatted(1)),
                body(fact + "," + supposed.replace("false", "null"), pair.formatted(1)),
                body(fact.substring(0, fact.length() - 1) + ", \"specific\": false}", ""),
                // 짝이 없음 · 둘 · 순번이 SUPPOSED 가 아닌 문장이나 범위 밖
                body(fact + "," + supposed, ""),
                body(fact + "," + supposed, pair.formatted(1) + "," + pair.formatted(1)),
                body(fact + "," + supposed, pair.formatted(0)),
                body(fact + "," + supposed, pair.formatted(2)),
                body(fact + "," + supposed, pair.formatted(-1)),
                // 자리 표시만 있고 순번이 없음은 기존 규칙
                body(fact + "," + supposed, pair.formatted(1) + ",{\"text\": \"빈칸\", \"question\": null, \"experienceId\": null, \"placeholder\": \"[결과]\", \"sentenceIndex\": null}"),
                // 전부 가정인 초안
                body(supposed, pair.formatted(0)),
                // omitted 10개 넘음
                body(fact, String.join(",", java.util.Collections.nCopies(11, gap("\"placeholder\": null, \"sentenceIndex\": null")))));
        for (String body : bodies) {
            expectInvalid(body);
        }
        assertNothingSaved();
    }

    // --- 보조 재료 경험의 근거 · 빈칸 ---

    @Test
    void 보조_재료_경험의_근거를_쓰면_출처_이름과_질문까지_풀어_준다() throws Exception {
        long ctx = saveExperience(actA, "보조 경험", SourceStatus.SOURCE_CONFIRMED, 1);
        Experience e = experiences.findById(ctx).orElseThrow();
        long ctxCitation = citations.save(new Citation(e, source, "보조 발췌", true, 2, 2)).getId();
        long ctxSupplement = supplements.save(new Supplement(e, null, null, "보조 질문?", "보조 답")).getId();
        long entered = saveExperience(actA, "내가 입력한 보조", SourceStatus.USER_ENTERED, 2);
        draftReply = new FakeReply(200, reply("""
                {"kind": "FACT", "text": "고른 근거", "evidence": [{"type": "CITATION", "id": %d}, {"type": "SUPPLEMENT", "id": %d}]},
                {"kind": "FACT", "text": "보조 근거", "evidence": [{"type": "CITATION", "id": %d}, {"type": "SUPPLEMENT", "id": %d}]},
                {"kind": "ELABORATED", "text": "보조 본문", "evidence": [{"type": "EXPERIENCE", "id": %d}], "note": "확인해 주세요"}
                """.formatted(chatCitation, chatSupplement, ctxCitation, ctxSupplement, entered), ""), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].type").value("CITATION"))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].sourceName").value(SOURCE_NAME))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].question").value(nullValue()))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[1].type").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[1].sourceName").value(nullValue()))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[1].question").value(SUPPLEMENT_QUESTION))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[0].experienceId").value(ctx))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[0].sourceName").value(SOURCE_NAME))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[0].text").value("보조 발췌"))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[1].question").value("보조 질문?"))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[1].sourceName").value(nullValue()))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].type").value("EXPERIENCE"))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].id").value(entered))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].sourceName").value(nullValue()))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].question").value(nullValue()))
                .andExpect(jsonPath("$.draft.sentences[2].note").value("확인해 주세요"));
    }

    @Test
    void 보조_재료로_보내지_않은_경험은_근거가_되지_못한다() throws Exception {
        long ctx = saveExperience(actA, "보조 경험", SourceStatus.SOURCE_CONFIRMED, 1);
        Experience e = experiences.findById(ctx).orElseThrow();
        long ctxCitation = citations.save(new Citation(e, source, "보조 발췌", true, 2, 2)).getId();
        long otherActivity = saveExperience(actB, "다른 활동의 USER_ENTERED", SourceStatus.USER_ENTERED, 1);
        List<String> sentences = List.of(
                // 보조 재료 경험이라도 USER_ENTERED 가 아니면 EXPERIENCE 근거가 아니다
                "{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}]}".formatted(ctx),
                // 고르지 않은 활동의 경험
                "{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}]}".formatted(otherActivity));
        for (String s : sentences) {
            expectInvalid("{\"sentences\": [" + s + "], \"omitted\": []}", true);
        }
        // 켜 두었으면 보조 재료의 발췌는 된다
        draftReply = new FakeReply(200, reply("{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}"
                .formatted(ctxCitation), ""), 0);
        jdbc.update("delete from analysis_usage");
        start(chat).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");
    }

    @Test
    void useContext_를_끄면_보조_재료_경험을_가리키는_응답은_틀린_응답이다() throws Exception {
        long ctx = saveExperience(actA, "보조 경험", SourceStatus.SOURCE_CONFIRMED, 1);
        Experience e = experiences.findById(ctx).orElseThrow();
        long ctxCitation = citations.save(new Citation(e, source, "보조 발췌", true, 2, 2)).getId();
        long ctxSupplement = supplements.save(new Supplement(e, null, null, "보조 질문?", "보조 답")).getId();
        long entered = saveExperience(actA, "내가 입력한 보조", SourceStatus.USER_ENTERED, 2);
        String fact = "{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"%s\", \"id\": %d}]}";
        String ok = okSentence(chatCitation);
        for (String body : List.of(
                "{\"sentences\": [" + fact.formatted("CITATION", ctxCitation) + "], \"omitted\": []}",
                "{\"sentences\": [" + fact.formatted("SUPPLEMENT", ctxSupplement) + "], \"omitted\": []}",
                "{\"sentences\": [" + fact.formatted("EXPERIENCE", entered) + "], \"omitted\": []}",
                "{\"sentences\": [" + ok + "], \"omitted\": [{\"text\": \"빈칸\", \"question\": \"질문?\", \"experienceId\": %d}]}"
                        .formatted(ctx))) {
            expectInvalid(body); // useContext false 로 시작한다
        }
    }

    @Test
    void 보조_재료_경험에_붙은_빈칸도_되묻기가_되고_답하면_그_경험의_보완이_된다() throws Exception {
        long ctx = saveExperience(actA, "보조 경험", SourceStatus.NEEDS_REVIEW, 1);
        draftReply = new FakeReply(200, reply(okSentence(chatCitation),
                "{\"text\": \"보조 경험의 빈칸\", \"question\": \"%s\", \"experienceId\": %d}".formatted(ASK, ctx)), 0);

        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat(((Number) JsonPath.read(done, "$.draft.omitted[0].experienceId")).longValue()).isEqualTo(ctx);
        // 추천에 없는 보조 재료 경험이라 FE 가 활동 · 경험 문장을 이 값으로 그린다(ETC#261)
        assertThat(((Number) JsonPath.read(done, "$.draft.omitted[0].activityId")).longValue()).isEqualTo(actA);
        assertThat((String) JsonPath.read(done, "$.draft.omitted[0].activityName")).isEqualTo("바꿔요 — 캠퍼스 중고거래 앱");
        assertThat((String) JsonPath.read(done, "$.draft.omitted[0].experienceContent")).isEqualTo("보조 경험");
        assertThat((Object) JsonPath.read(done, "$.draft.omitted[0].supplement")).isNull();
        long asked = ((Number) JsonPath.read(done, "$.draft.omitted[0].clarificationId")).longValue();
        assertThat(jdbc.queryForObject("select count(*) from clarification where experience_id = ?", Integer.class, ctx)).isEqualTo(1);

        mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\": \"두 기기로 확인했다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.experienceId").value(ctx))
                .andExpect(jsonPath("$.supplement.question").value(ASK));
        assertThat(jdbc.queryForObject("select count(*) from supplement where experience_id = ?", Integer.class, ctx)).isEqualTo(1);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted[0].supplementId").isNumber())
                .andExpect(jsonPath("$.draft.omitted[0].supplement.answer").value("두 기기로 확인했다"))
                .andExpect(jsonPath("$.draft.omitted[0].supplement.question").value(ASK))
                .andExpect(jsonPath("$.draft.omitted[0].experienceContent").value("보조 경험"));
    }

    // --- 분석 횟수(S-77) ---

    @Test
    void 다시_만들기는_빠진_사실에_답하기_전엔_세고_답한_뒤엔_세지_않는다() throws Exception {
        draftReply = new FakeReply(200, reply(okSentence(chatCitation),
                "{\"text\": \"확인 방법\", \"question\": \"%s\", \"experienceId\": %d}".formatted(ASK, chat)), 0);
        int base = used(); // 문항 추천이 이미 1회 썼다

        start(chat).andExpect(status().isAccepted());
        String first = awaitDraft();
        assertThat(used()).isEqualTo(base + 1);

        // 답하지 않고 다시 만들면 센다
        start(chat).andExpect(status().isAccepted());
        String second = awaitDraft();
        assertThat(used()).isEqualTo(base + 2);

        // 새 초안의 빠진 사실에 답하면 — 다시 만들어도 세지 않는다. 한도를 다 써도 만들어진다
        long asked = ((Number) JsonPath.read(second, "$.draft.omitted[0].clarificationId")).longValue();
        assertThat(asked).isNotEqualTo(((Number) JsonPath.read(first, "$.draft.omitted[0].clarificationId")).longValue());
        mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\": \"두 기기로 확인했다\"}")).andExpect(status().isCreated());
        assertThat(used()).isEqualTo(base + 2);
        jdbc.update("update app_user set daily_analysis_limit = ? where id = ?", used(), TestLogin.USER_ID);

        start(chat).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");
        assertThat(used()).isEqualTo(base + 2);

        // 그 새 초안은 답이 없으니 한도에 걸린다
        expectError(start(chat), 429, "DAILY_LIMIT_EXCEEDED");
        assertThat(used()).isEqualTo(base + 2);
    }

    // --- 옛 초안(V32 이전) ---

    @Test
    void V32_이전_초안은_보조_재료를_썼고_새_칸은_null_이다() throws Exception {
        jdbc.update("insert into draft (question_id, status) values (?, 'COMPLETED')", questionId);
        long draftId = jdbc.queryForObject("select id from draft where question_id = ?", Long.class, questionId);
        jdbc.update("insert into draft_omitted (draft_id, position, text) values (?, 0, '옛 빈칸')", draftId);

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.useContext").value(true))
                .andExpect(jsonPath("$.draft.omitted[0].text").value("옛 빈칸"))
                .andExpect(jsonPath("$.draft.omitted[0].placeholder").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[0].sentenceIndex").value(nullValue()));
    }

    // --- PATCH /api/questions/{id} 목표 글자 수(S-77) ---

    @Test
    void 목표_글자_수만_보내면_그것만_바뀌고_유형_출처는_그대로다() throws Exception {
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.targetLength").value(nullValue()));

        // 유형이 아직 없는 문항
        patchQuestion(questionId, "{\"targetLength\": 800}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(questionId))
                .andExpect(jsonPath("$.targetLength").value(800))
                .andExpect(jsonPath("$.questionType").value(nullValue()))
                .andExpect(jsonPath("$.questionTypeBy").value(nullValue()));

        // 유형을 고른 뒤 — 글자 수만 고치면 유형 · 출처가 그대로, 유형만 고치면 글자 수가 그대로
        patchQuestion(questionId, "{\"questionType\": \"ACHIEVEMENT\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.questionType").value("ACHIEVEMENT"))
                .andExpect(jsonPath("$.questionTypeBy").value("USER"))
                .andExpect(jsonPath("$.targetLength").value(800));
        patchQuestion(questionId, "{\"targetLength\": 1500}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetLength").value(1500))
                .andExpect(jsonPath("$.questionType").value("ACHIEVEMENT"))
                .andExpect(jsonPath("$.questionTypeBy").value("USER"));
        patchQuestion(questionId, "{\"questionType\": \"GROWTH\", \"targetLength\": 100}").andExpect(status().isOk())
                .andExpect(jsonPath("$.questionType").value("GROWTH"))
                .andExpect(jsonPath("$.targetLength").value(100));

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.targetLength").value(100))
                .andExpect(jsonPath("$.questionType").value("GROWTH"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendations.length()").value(3));
        mvc.perform(get("/api/questions"))
                .andExpect(jsonPath("$.questions[0].id").value(questionId))
                .andExpect(jsonPath("$.questions[0].targetLength").value(100));
    }

    @Test
    void 목표_글자_수는_null_로_지우고_경계값은_받는다() throws Exception {
        patchQuestion(questionId, "{\"targetLength\": 5000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetLength").value(5000));
        patchQuestion(questionId, "{\"targetLength\": 100}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetLength").value(100));
        patchQuestion(questionId, "{\"targetLength\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetLength").value(nullValue()));
        mvc.perform(get("/api/questions/{id}", questionId)).andExpect(jsonPath("$.targetLength").value(nullValue()));
        assertThat(jdbc.queryForObject("select target_length from question where id = ?", Integer.class, questionId)).isNull();
    }

    @Test
    void 문항_고치기_오류는_400_이고_아무것도_바꾸지_않는다() throws Exception {
        patchQuestion(questionId, "{\"targetLength\": 700, \"questionType\": \"OTHER\"}").andExpect(status().isOk());

        expectError(patchQuestion(questionId, "{}"), 400, "INVALID_REQUEST");
        expectError(patchQuestion(questionId, "{\"other\": 1}"), 400, "INVALID_REQUEST");
        expectError(mvc.perform(patch("/api/questions/{id}", questionId)), 400, "INVALID_REQUEST");
        expectError(patchQuestion(questionId, "{\"questionType\": null}"), 400, "INVALID_REQUEST");
        expectError(patchQuestion(questionId, "{\"questionType\": null, \"targetLength\": 900}"), 400, "INVALID_REQUEST");
        for (String bad : List.of("99", "5001", "0", "-1", "500.5", "\"500\"", "true", "[]")) {
            expectError(patchQuestion(questionId, "{\"targetLength\": " + bad + "}"), 400, "INVALID_REQUEST");
        }
        expectError(patchQuestion(questionId, "{\"targetLength\": 99, \"questionType\": \"GROWTH\"}"), 400, "INVALID_REQUEST");

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.targetLength").value(700))
                .andExpect(jsonPath("$.questionType").value("OTHER"));
    }

    @Test
    void 없는_문항을_고치면_404() throws Exception {
        expectError(patchQuestion(999_999, "{\"targetLength\": 500}"), 404, "QUESTION_NOT_FOUND");
        expectError(patchQuestion(999_999, "{\"questionType\": \"OTHER\"}"), 404, "QUESTION_NOT_FOUND");
    }

    // --- 도우미 ---

    private long saveExperience(long activityId, String content, SourceStatus status, int minutesAgo) {
        return saveExperience(activityId, content, status, minutesAgo, Competency.LEARNING);
    }

    private long saveExperience(long activityId, String content, SourceStatus status, int minutesAgo, Competency competency) {
        Experience e = experiences.save(new Experience(activities.findById(activityId).orElseThrow(), content,
                List.of(competency), CompetenciesBy.AI, Actor.ME, status, position++));
        setCreated(e.getId(), minutesAgo);
        return e.getId();
    }

    private void setCreated(long experienceId, int minutesAgo) {
        jdbc.update("update experience set created_at = now() - make_interval(mins => ?) where id = ?", minutesAgo, experienceId);
    }

    // 외부 AI 로 답해 확인 전인 보완을 만든다
    private void answeredByExternalAi(long experienceId) {
        long asked = jdbc.queryForObject("insert into clarification (question_id, position, experience_id, question) "
                + "values (?, 0, ?, '외부 AI 되묻기') returning id", Long.class, questionId, experienceId);
        mcpWrites.answerClarification(TestLogin.USER_ID, "claude-ai", asked, "확인 전인 답");
    }

    private String okSentence(long citationId) {
        return "{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}".formatted(citationId);
    }

    private static String gap(String extra) {
        return "{\"text\": \"빈칸\", \"question\": null, \"experienceId\": null, " + extra + "}";
    }

    private static String body(String sentence, String omitted) {
        return "{\"sentences\": [" + sentence + "], \"omitted\": [" + omitted + "]}";
    }

    // sentences · omitted 쪽 JSON 조각으로 응답 본문을 만든다
    private static String reply(String sentences, String omitted) {
        return "{\"sentences\": [" + sentences + "], \"omitted\": [" + omitted + "]}";
    }

    private List<Long> ids(String json, String path) {
        List<Number> found = JsonPath.read(json, path);
        return found.stream().map(Number::longValue).toList();
    }

    private List<Long> contextIds(String json) {
        return ids(json, "$.contextExperiences[*].id");
    }

    private int used() {
        return jdbc.queryForObject("select count from analysis_usage where user_id = ?", Integer.class, TestLogin.USER_ID);
    }

    // useContext 를 끄고 시작해 이 응답 본문이면 초안이 INVALID_RESPONSE 로 실패한다
    private void expectInvalid(String responseBody) throws Exception {
        expectInvalid(responseBody, false);
    }

    private void expectInvalid(String responseBody, boolean useContext) throws Exception {
        jdbc.update("delete from analysis_usage where user_id = ?", TestLogin.USER_ID); // 경우가 많아 하루 한도에 닿지 않게
        draftReply = new FakeReply(200, responseBody, 0);
        postJson("{\"experienceIds\": [%d], \"useContext\": %s}".formatted(chat, useContext)).andExpect(status().isAccepted());
        String failed = awaitDraft();
        assertThat((String) JsonPath.read(failed, "$.draft.status")).as(responseBody).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.draft.error.code")).as(responseBody).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.draft.error.message")).as(responseBody).startsWith("INVALID_RESPONSE");
    }

    private void assertNothingSaved() {
        assertThat(jdbc.queryForObject("select count(*) from draft_sentence", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from draft_omitted", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isZero();
    }

    private ResultActions start(long... ids) throws Exception {
        return postJson("{\"experienceIds\": " + java.util.Arrays.toString(ids) + "}");
    }

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/questions/{id}/draft", questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patchQuestion(long id, String json) throws Exception {
        return mvc.perform(patch("/api/questions/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void awaitQuestion() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/questions/{id}", questionId)).andReturn().getResponse().getContentAsString();
            if ("COMPLETED".equals(JsonPath.read(body, "$.status"))) {
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
            String body = mvc.perform(get("/api/questions/{id}", questionId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
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

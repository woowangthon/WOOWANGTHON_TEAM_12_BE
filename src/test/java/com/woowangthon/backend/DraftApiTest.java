package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.woowangthon.backend.job.JobStatus;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.Draft;
import com.woowangthon.backend.question.DraftRepository;
import com.woowangthon.backend.question.InterruptedQuestionCleaner;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionRepository;
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
 * ETC docs/contracts/question-api.md 「답변 초안」 과 ai-api.md 「POST /draft」(SPEC S-27).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 초안이 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=1")
class DraftApiTest {

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
            // 제한 시간 테스트에서 BE 가 먼저 끊은 경우
        }
        exchange.close();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    static final String S1 = "「바꿔요」 프로젝트에서 채팅 메시지가 늦게 도착하는 문제가 있었고, 제가 폴링을 WebSocket 으로 바꿔 해결했습니다.";
    static final String S2 = "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨습니다.";
    static final String S3 = "스터디원 6명의 풀이를 매주 리뷰했습니다.";
    static final String QUOTE = "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.";
    static final String ANSWER = "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다";

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
    ApplicationRepository applications;

    @Autowired
    QuestionRepository questions;

    @Autowired
    DraftRepository drafts;

    @Autowired
    InterruptedQuestionCleaner cleaner;

    MockMvc mvc;

    long appId;
    long chat;       // 추천됨 — ME · 원본에서 찾은 근거 + 못 찾은 근거 + 보완
    long study;      // 추천됨 — 직접 넣은 경험(USER_ENTERED)
    long bare;       // 추천됨 — 받칠 것이 없다(못 찾은 근거뿐)
    long stranger;   // 추천 안 됨
    long chatCitation;
    long unmatchedCitation;
    long chatSupplement;
    long questionId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        Source source = sources.save(new Source(TestLogin.USER_ID, SourceKind.FILE, "바꿔요-회고.md", "회고"));
        Activity app = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        appId = app.getId();
        Experience e101 = experiences.save(new Experience(app, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING), CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0));
        Experience e106 = experiences.save(new Experience(app, "스터디원 6명의 풀이를 매주 리뷰했다",
                List.of(Competency.LEARNING), CompetenciesBy.USER, Actor.ME, SourceStatus.USER_ENTERED, 1));
        Experience e107 = experiences.save(new Experience(app, "받칠 것이 없는 경험",
                List.of(Competency.LEARNING), CompetenciesBy.AI, Actor.ME, SourceStatus.NEEDS_REVIEW, 2));
        Experience e108 = experiences.save(new Experience(app, "추천되지 않은 경험",
                List.of(Competency.LEARNING), CompetenciesBy.AI, Actor.ME, SourceStatus.USER_ENTERED, 3));
        chat = e101.getId();
        study = e106.getId();
        bare = e107.getId();
        stranger = e108.getId();
        chatCitation = citations.save(new Citation(e101, source, QUOTE, true, 5, 5)).getId();
        unmatchedCitation = citations.save(new Citation(e101, source, "원본에서 못 찾은 발췌", false, null, null)).getId();
        citations.save(new Citation(e107, source, "역시 못 찾은 발췌", false, null, null));
        chatSupplement = supplements.save(new Supplement(e101, null, null,
                "폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?", ANSWER)).getId();

        matchReply = new FakeReply(200, """
                {
                  "requirements": [{ "text": "기술 문제 해결", "status": "SUPPORTED", "experienceIds": [%d] }],
                  "recommendations": [
                    { "experienceId": %d, "reason": "추천 이유 비밀", "emphasis": "강조점 비밀", "evidence": [{ "type": "CITATION", "id": %d }], "gaps": ["부족한 점 비밀"] },
                    { "experienceId": %d, "reason": "이유", "emphasis": "강조", "evidence": [], "gaps": [] },
                    { "experienceId": %d, "reason": "이유", "emphasis": "강조", "evidence": [], "gaps": [] }
                  ],
                  "clarifications": [],
                  "model": "test-model"
                }
                """.formatted(chat, chat, chatCitation, study, bare), 0);
        draftReply = new FakeReply(200, contractExample(), 0);

        String created = mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization\": \"OO 백엔드 부트캠프\", \"text\": \"기술적인 문제를 해결한 경험을 적어 주세요.\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        questionId = ((Number) JsonPath.read(created, "$.questionId")).longValue();
        awaitQuestion("COMPLETED");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table draft_sentence_evidence, draft_sentence, draft_omitted, draft_experience, draft, "
                + "grounding_sentence_evidence, grounding_sentence, grounding_check, "
                + "question_recommendation_evidence, question_recommendation, question_requirement, supplement, clarification, "
                + "question, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity, source_section, source");
    }

    private String contractExample() {
        return """
                {
                  "sentences": [
                    { "text": "%s", "evidence": [{ "type": "CITATION", "id": %d }] },
                    { "text": "%s", "evidence": [{ "type": "SUPPLEMENT", "id": %d }] },
                    { "text": "%s", "evidence": [{ "type": "EXPERIENCE", "id": %d }] }
                  ],
                  "omitted": ["바꾼 뒤 메시지가 제때 오는지 확인한 방법", "이 경험에서 배운 점 · 느낀 점"],
                  "model": "test-model"
                }
                """.formatted(S1, chatCitation, S2, chatSupplement, S3, study);
    }

    private int used() {
        return jdbc.queryForObject("select count from analysis_usage where user_id = ?", Integer.class, TestLogin.USER_ID);
    }

    // --- 시작 → 완료 ---

    @Test
    void 만들면_문장마다_근거가_저장되고_새로고침해도_남는다() throws Exception {
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft").value(nullValue()));
        int before = used();

        start(study, chat).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.questionId").value(questionId))
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.experienceIds[0]").value(study))
                .andExpect(jsonPath("$.experienceIds[1]").value(chat))
                .andExpect(jsonPath("$.sentences.length()").value(0))
                .andExpect(jsonPath("$.omitted.length()").value(0));

        String done = awaitDraft();
        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(done, "$.draft.createdAt")).matches(".*\\+09:00");

        // AI 에는 문항과 고른 경험만, 고른 순서대로 — 근거는 원본에서 찾은 것만, 보완은 전부, 추천의 해석은 싣지 않는다
        assertThat(RECEIVED).hasSize(1);
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.question.organization")).isEqualTo("OO 백엔드 부트캠프");
        assertThat((String) JsonPath.read(sent, "$.question.text")).isEqualTo("기술적인 문제를 해결한 경험을 적어 주세요.");
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[*].id")).containsExactly((int) study, (int) chat);
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[1].citations[*].id")).containsExactly((int) chatCitation);
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[1].supplements[*].id")).containsExactly((int) chatSupplement);
        assertThat((String) JsonPath.read(sent, "$.experiences[0].sourceStatus")).isEqualTo("USER_ENTERED");
        assertThat(sent).doesNotContain("비밀");

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.experienceIds.length()").value(2))
                .andExpect(jsonPath("$.draft.sentences.length()").value(3))
                .andExpect(jsonPath("$.draft.sentences[0].text").value(S1))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].type").value("CITATION"))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].id").value(chatCitation))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].experienceId").value(chat))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].activityId").value(appId))
                .andExpect(jsonPath("$.draft.sentences[0].evidence[0].text").value(QUOTE))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[0].type").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[0].text").value(ANSWER))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].type").value("EXPERIENCE"))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].id").value(study))
                .andExpect(jsonPath("$.draft.sentences[2].evidence[0].text").value("스터디원 6명의 풀이를 매주 리뷰했다"))
                .andExpect(jsonPath("$.draft.omitted.length()").value(2))
                .andExpect(jsonPath("$.draft.omitted[0]").value("바꾼 뒤 메시지가 제때 오는지 확인한 방법"));

        // 분석 1회로 센다 · 문항의 추천은 그대로
        assertThat(used()).isEqualTo(before + 1);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendations.length()").value(3));
    }

    @Test
    void 활동의_계기는_초안_요청에_싣지_않는다() throws Exception {
        Activity app = activities.findById(appId).orElseThrow();
        app.changeNote("계기 비밀");
        activities.save(app);

        start(chat).andExpect(status().isAccepted());
        awaitDraft();

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.getFirst()).doesNotContain("계기 비밀").doesNotContain("note");
    }

    @Test
    void 받칠_것이_없으면_문장_없이_완료된다() throws Exception {
        draftReply = new FakeReply(200, "{\"sentences\": [], \"omitted\": [\"경험 전부\"]}", 0);
        start(bare).andExpect(status().isAccepted());

        String done = awaitDraft();
        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.draft.sentences")).isEmpty();
        assertThat((List<String>) JsonPath.read(done, "$.draft.omitted")).containsExactly("경험 전부");
        assertThat((List<?>) JsonPath.read(RECEIVED.getFirst(), "$.experiences[0].citations")).isEmpty();
    }

    @Test
    void 다시_만들면_예전_것을_바꾸고_지워진_것은_근거에서_빠진다() throws Exception {
        start(chat, study).andExpect(status().isAccepted());
        awaitDraft();

        // 보완을 고치면 글은 지금 상태로, 지우면 근거에서 빠진다 — 문장은 그대로
        jdbc.update("update supplement set answer = '고친 답' where id = ?", chatSupplement);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.sentences[1].evidence[0].text").value("고친 답"));
        jdbc.update("delete from supplement where id = ?", chatSupplement);
        jdbc.update("delete from experience where id = ?", study);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.experienceIds.length()").value(1))
                .andExpect(jsonPath("$.draft.experienceIds[0]").value(chat))
                .andExpect(jsonPath("$.draft.sentences.length()").value(3))
                .andExpect(jsonPath("$.draft.sentences[0].evidence.length()").value(1))
                .andExpect(jsonPath("$.draft.sentences[1].text").value(S2))
                .andExpect(jsonPath("$.draft.sentences[1].evidence.length()").value(0))
                .andExpect(jsonPath("$.draft.sentences[2].evidence.length()").value(0));

        // 그새 지워진 경험은 고를 수 없다
        expectError(start(study), 400, "INVALID_REQUEST");

        draftReply = new FakeReply(200, """
                {"sentences": [{"text": "새 문장", "evidence": [{"type": "CITATION", "id": %d}]}], "omitted": []}"""
                .formatted(chatCitation), 0);
        start(chat).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sentences.length()").value(0));
        String done = awaitDraft();
        assertThat((List<String>) JsonPath.read(done, "$.draft.sentences[*].text")).containsExactly("새 문장");
        assertThat((List<?>) JsonPath.read(done, "$.draft.omitted")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from draft", Integer.class)).isEqualTo(1);
    }

    @Test
    void 만드는_중이면_409_이고_예전_것을_바꾸지_않는다() throws Exception {
        draftReply = new FakeReply(200, contractExample(), 300);
        start(chat, study).andExpect(status().isAccepted());
        int before = used();

        expectError(start(chat), 409, "DRAFT_IN_PROGRESS");

        String done = awaitDraft();
        assertThat((List<Integer>) JsonPath.read(done, "$.draft.experienceIds")).containsExactly((int) chat, (int) study);
        assertThat(used()).isEqualTo(before);
        assertThat(RECEIVED).hasSize(1);
    }

    @Test
    void 한도를_다_썼으면_429_이고_예전_것을_바꾸지_않는다() throws Exception {
        start(chat, study).andExpect(status().isAccepted());
        awaitDraft();
        jdbc.update("update app_user set daily_analysis_limit = ? where id = ?", used(), TestLogin.USER_ID);

        expectError(start(chat), 429, "DAILY_LIMIT_EXCEEDED");

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.status").value("COMPLETED"))
                .andExpect(jsonPath("$.draft.experienceIds.length()").value(2))
                .andExpect(jsonPath("$.draft.sentences.length()").value(3));
    }

    // --- 실패 ---

    @Test
    void AI_가_오류를_주면_실패로_남고_같은_POST_로_다시_만든다() throws Exception {
        draftReply = new FakeReply(502, "{\"error\": {\"code\": \"LLM_INVALID_OUTPUT\", \"message\": \"x\"}}", 0);
        start(chat, study).andExpect(status().isAccepted());

        String failed = awaitDraft();
        assertThat((String) JsonPath.read(failed, "$.draft.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.draft.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.draft.error.message")).startsWith("LLM_INVALID_OUTPUT");
        assertThat((List<?>) JsonPath.read(failed, "$.draft.sentences")).isEmpty();
        assertThat((List<?>) JsonPath.read(failed, "$.draft.omitted")).isEmpty();
        assertThat((List<Integer>) JsonPath.read(failed, "$.draft.experienceIds")).containsExactly((int) chat, (int) study);

        draftReply = new FakeReply(200, contractExample(), 0);
        start(chat, study).andExpect(status().isAccepted());
        String done = awaitDraft();
        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(done, "$.draft.error")).isNull();
    }

    @Test
    void 제한_시간을_넘기면_AI_TIMEOUT() throws Exception {
        draftReply = new FakeReply(200, contractExample(), 2000);
        start(chat).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.error.code")).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 계약을_어긴_응답이면_아무것도_저장하지_않고_실패() throws Exception {
        String okSentence = "{\"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}".formatted(chatCitation);
        List<String> bodies = List.of(
                // 받칠 것이 있는데 문장 0개
                "{\"sentences\": [], \"omitted\": []}",
                "{\"omitted\": []}",
                // 15개 넘음
                "{\"sentences\": [" + String.join(",", java.util.Collections.nCopies(16, okSentence)) + "]}",
                // 빈 문장 · 줄바꿈
                "{\"sentences\": [{\"text\": \" \", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                "{\"sentences\": [{\"text\": \"한 줄\\n두 줄\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                // 근거 0개 · 지어낸 ID · 원본에서 못 찾아 보내지 않은 근거 · 값 목록 밖 · 같은 근거 두 번
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": []}]}",
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": 999999}]}]}",
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(unmatchedCitation),
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"GUESS\", \"id\": 1}]}]}",
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}, {\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation, chatCitation),
                // USER_ENTERED 가 아닌 경험 본문을 근거로
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}]}]}".formatted(chat),
                // 보내지 않은 경험의 본문을 근거로
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}]}]}".formatted(stranger),
                // omitted 5개 넘음 · 빈 문자열
                "{\"sentences\": [" + okSentence + "], \"omitted\": [\"1\", \"2\", \"3\", \"4\", \"5\", \"6\"]}",
                "{\"sentences\": [" + okSentence + "], \"omitted\": [\" \"]}");
        for (String body : bodies) {
            draftReply = new FakeReply(200, body, 0);
            start(chat, study).andExpect(status().isAccepted());
            String failed = awaitDraft();
            assertThat((String) JsonPath.read(failed, "$.draft.status")).as(body).isEqualTo("FAILED");
            assertThat((String) JsonPath.read(failed, "$.draft.error.code")).as(body).isEqualTo("AI_FAILED");
            assertThat((String) JsonPath.read(failed, "$.draft.error.message")).as(body).startsWith("INVALID_RESPONSE");
        }
        assertThat(jdbc.queryForObject("select count(*) from draft_sentence", Integer.class)).isZero();
    }

    @Test
    void 서버가_다시_켜지면_만들던_초안은_실패가_된다() {
        Draft draft = new Draft(questionId);
        draft.start(List.of(chat));
        drafts.save(draft);

        cleaner.run(null);

        Draft after = drafts.findByQuestionId(questionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    // --- 요청 오류 ---

    @Test
    void 시작_오류() throws Exception {
        int before = used();
        expectError(postJson("{}"), 400, "INVALID_REQUEST");
        expectError(postJson("{\"experienceIds\": []}"), 400, "INVALID_REQUEST");
        expectError(postJson("{\"experienceIds\": [null]}"), 400, "INVALID_REQUEST");
        expectError(postJson("{\"experienceIds\": [\"글자\"]}"), 400, "INVALID_REQUEST");
        expectError(mvc.perform(post("/api/questions/{id}/draft", questionId)), 400, "INVALID_REQUEST");
        expectError(start(chat, study, bare, stranger), 400, "INVALID_REQUEST");
        expectError(start(chat, chat), 400, "INVALID_REQUEST");
        expectError(start(stranger), 400, "INVALID_REQUEST");
        expectError(start(chat, 999999L), 400, "INVALID_REQUEST");
        expectError(mvc.perform(post("/api/questions/{id}/draft", 999999)
                .contentType(MediaType.APPLICATION_JSON).content("{\"experienceIds\": [1]}")), 404, "QUESTION_NOT_FOUND");

        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            MockMvc otherMvc = TestLogin.mvc(context, other);
            expectError(otherMvc.perform(post("/api/questions/{id}/draft", questionId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"experienceIds\": [" + chat + "]}")),
                    404, "QUESTION_NOT_FOUND");
            expectError(otherMvc.perform(get("/api/questions/{id}", questionId)), 404, "QUESTION_NOT_FOUND");
        } finally {
            jdbc.update("delete from app_user where id = ?", other);
        }
        assertThat(RECEIVED).isEmpty();
        assertThat(used()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from draft", Integer.class)).isZero();
    }

    @Test
    void 추천이_끝나지_않은_문항엔_만들_수_없다() throws Exception {
        Application application = applications.save(new Application(TestLogin.USER_ID, "다른 지원"));
        Question analyzing = questions.save(new Question(application, "분석 중인 문항"));
        Question failed = new Question(application, "실패한 문항");
        failed.fail("AI_FAILED", "실패");
        questions.save(failed);

        for (Question q : List.of(analyzing, failed)) {
            expectError(mvc.perform(post("/api/questions/{id}/draft", q.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"experienceIds\": [" + chat + "]}")),
                    409, "QUESTION_NOT_COMPLETED");
        }
        assertThat(RECEIVED).isEmpty();
    }

    private ResultActions start(long... ids) throws Exception {
        return postJson("{\"experienceIds\": " + java.util.Arrays.toString(ids) + "}");
    }

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/questions/{id}/draft", questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void awaitQuestion(String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/questions/{id}", questionId)).andReturn().getResponse().getContentAsString();
            if (expected.equals(JsonPath.read(body, "$.status"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("문항 " + questionId + " 이(가) " + expected + " 가 되지 않았습니다");
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

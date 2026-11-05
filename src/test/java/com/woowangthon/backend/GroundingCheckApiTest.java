package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import com.woowangthon.backend.question.GroundingCheck;
import com.woowangthon.backend.question.GroundingCheckRepository;
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
 * ETC docs/contracts/question-api.md 「근거 점검」 과 ai-api.md 「POST /ground-check」(SPEC S-45).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 점검이 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=1")
class GroundingCheckApiTest {

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
        FAKE_AI.createContext("/ground-check", exchange -> {
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

    // 계약 예시의 글(문장 다섯)
    static final String S1 = "저는 「바꿔요」 프로젝트에서 채팅 메시지가 늦게 도착하는 문제를 WebSocket 으로 바꿔 해결했습니다.";
    static final String S2 = "서버에 STOMP 엔드포인트를 만들고 앱의 폴링 코드를 구독 방식으로 바꿨습니다.";
    static final String S3 = "그 결과 응답 속도가 80% 빨라졌고 사용자 만족도가 크게 올랐습니다.";
    static final String S4 = "스터디원 6명의 풀이를 매주 리뷰했습니다.";
    static final String S5 = "이 경험으로 문제의 원인을 끝까지 찾는 개발자가 되고 싶습니다.";
    static final String TEXT = String.join(" ", S1, S2, S3, S4, S5);

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
    GroundingCheckRepository checks;

    @Autowired
    InterruptedQuestionCleaner cleaner;

    MockMvc mvc;

    long appId;
    long studyId;
    long chat;       // 101 — ME · 원본에서 찾은 근거 + 못 찾은 근거 + 보완
    long study;      // 106 — 직접 넣은 경험(USER_ENTERED)
    long chatCitation;
    long unmatchedCitation;
    long chatSupplement;
    long questionId;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        Source source = sources.save(new Source(TestLogin.USER_ID, SourceKind.FILE, "바꿔요-회고.md", "회고"));
        Activity app = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        Activity studyGroup = activities.save(new Activity(TestLogin.USER_ID, "알고리즘 스터디",
                new Period("2024-09", null, true)));
        appId = app.getId();
        studyId = studyGroup.getId();
        Experience e101 = experiences.save(new Experience(app, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING), CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0));
        Experience e106 = experiences.save(new Experience(studyGroup, "스터디원 6명의 풀이를 매주 리뷰했다",
                List.of(Competency.LEARNING), CompetenciesBy.USER, Actor.ME, SourceStatus.USER_ENTERED, 0));
        chat = e101.getId();
        study = e106.getId();
        chatCitation = citations.save(new Citation(e101, source,
                "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.", true, 5, 5)).getId();
        unmatchedCitation = citations.save(new Citation(e101, source, "원본에서 못 찾은 발췌", false, null, null)).getId();
        chatSupplement = supplements.save(new Supplement(e101, null, null,
                "폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?",
                "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다")).getId();

        Application application = applications.save(new Application(TestLogin.USER_ID, "OO 백엔드 부트캠프"));
        // 추천은 돌리지 않는다 — 점검은 문항의 추천 작업과 따로 돈다(문항이 ANALYZING 이어도 된다)
        questionId = questions.save(new Question(application, "기술적인 문제를 해결한 경험을 적어 주세요.")).getId();
        reply = new FakeReply(200, contractExample(), 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table new_fact_candidate, answer_dismissed, answer, draft_sentence_evidence, draft_sentence, draft_omitted, draft_guideline, draft_experience, draft, grounding_sentence_evidence, grounding_sentence, grounding_check, "
                + "question_recommendation_evidence, question_recommendation, question_requirement, supplement, clarification, "
                + "question, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity_link, activity, source_section, source");
    }

    private String contractExample() {
        return """
                {
                  "sentences": [
                    { "text": "%s", "status": "SOURCE", "evidence": [{ "type": "CITATION", "id": %d }, { "type": "SUPPLEMENT", "id": %d }], "note": null },
                    { "text": "%s", "status": "USER_INPUT", "evidence": [{ "type": "SUPPLEMENT", "id": %d }], "note": null },
                    { "text": "%s", "status": "UNSUPPORTED", "evidence": [], "note": "문장의 숫자(80)가 받치는 근거 · 보완에 없어요" },
                    { "text": "%s", "status": "USER_INPUT", "evidence": [{ "type": "EXPERIENCE", "id": %d }], "note": null },
                    { "text": "%s", "status": "OPINION", "evidence": [], "note": null }
                  ],
                  "model": "test-model"
                }
                """.formatted(S1, chatCitation, chatSupplement, S2, chatSupplement, S3, S4, study, S5);
    }

    // --- 시작 → 완료 ---

    @Test
    void 점검하면_문장마다_판정과_근거가_저장되고_새로고침해도_남는다() throws Exception {
        mvc.perform(get("/api/questions/{id}/grounding-check", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundingCheck").value(nullValue()));

        start(TEXT).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.questionId").value(questionId))
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.text").value(TEXT))
                .andExpect(jsonPath("$.sentences.length()").value(0));

        String done = awaitDone();
        assertThat((String) JsonPath.read(done, "$.groundingCheck.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(done, "$.groundingCheck.createdAt")).matches(".*\\+09:00");

        // AI 에는 경험 전부 — 근거는 원본에서 찾은 것만, 보완은 전부
        assertThat(RECEIVED).hasSize(1);
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.text")).isEqualTo(TEXT);
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[*].id")).containsExactlyInAnyOrder((int) chat, (int) study);
        String chatPath = "$.experiences[?(@.id == " + chat + ")]";
        assertThat((List<Integer>) JsonPath.read(sent, chatPath + ".citations[*].id")).containsExactly((int) chatCitation);
        assertThat((List<Integer>) JsonPath.read(sent, chatPath + ".supplements[*].id")).containsExactly((int) chatSupplement);
        assertThat((List<String>) JsonPath.read(sent, "$.experiences[?(@.id == " + study + ")].sourceStatus"))
                .containsExactly("USER_ENTERED");

        mvc.perform(get("/api/questions/{id}/grounding-check", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundingCheck.sentences.length()").value(5))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].text").value(S1))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].status").value("SOURCE"))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence.length()").value(2))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence[0].type").value("CITATION"))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence[0].id").value(chatCitation))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence[0].experienceId").value(chat))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence[0].activityId").value(appId))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence[0].text").value(
                        "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다."))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].note").value(nullValue()))
                .andExpect(jsonPath("$.groundingCheck.sentences[1].status").value("USER_INPUT"))
                .andExpect(jsonPath("$.groundingCheck.sentences[1].evidence[0].type").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.groundingCheck.sentences[1].evidence[0].text").value(
                        "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다"))
                .andExpect(jsonPath("$.groundingCheck.sentences[2].status").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.groundingCheck.sentences[2].evidence.length()").value(0))
                .andExpect(jsonPath("$.groundingCheck.sentences[2].note").value("문장의 숫자(80)가 받치는 근거 · 보완에 없어요"))
                .andExpect(jsonPath("$.groundingCheck.sentences[3].evidence[0].type").value("EXPERIENCE"))
                .andExpect(jsonPath("$.groundingCheck.sentences[3].evidence[0].id").value(study))
                .andExpect(jsonPath("$.groundingCheck.sentences[3].evidence[0].experienceId").value(study))
                .andExpect(jsonPath("$.groundingCheck.sentences[3].evidence[0].activityId").value(studyId))
                .andExpect(jsonPath("$.groundingCheck.sentences[3].evidence[0].text").value("스터디원 6명의 풀이를 매주 리뷰했다"))
                .andExpect(jsonPath("$.groundingCheck.sentences[4].status").value("OPINION"));

        // 분석 1회로 센다
        assertThat(jdbc.queryForObject("select count from analysis_usage where user_id = ?", Integer.class,
                TestLogin.USER_ID)).isEqualTo(1);
        // 문항의 추천 작업은 건드리지 않는다
        assertThat(questions.findById(questionId).orElseThrow().getStatus()).isEqualTo(JobStatus.ANALYZING);
    }

    @Test
    void 경험이_없어도_AI_를_부른다() throws Exception {
        jdbc.execute("truncate table new_fact_candidate, answer_dismissed, answer, supplement, citation, experience, activity_link, activity");
        reply = new FakeReply(200, """
                {"sentences": [{"text": "%s", "status": "UNSUPPORTED", "evidence": [], "note": "자료에 없어요"}]}"""
                .formatted(S3), 0);
        start(S3).andExpect(status().isAccepted());

        String done = awaitDone();
        assertThat((String) JsonPath.read(done, "$.groundingCheck.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(RECEIVED.getFirst(), "$.experiences")).isEmpty();
    }

    @Test
    void 다시_점검하면_예전_것을_바꾸고_지워진_근거는_빠진다() throws Exception {
        start(TEXT).andExpect(status().isAccepted());
        awaitDone();

        // 그새 지운 보완은 근거에서 빠진다 — 판정은 그대로
        jdbc.update("delete from supplement where id = ?", chatSupplement);
        mvc.perform(get("/api/questions/{id}/grounding-check", questionId))
                .andExpect(jsonPath("$.groundingCheck.sentences[0].evidence.length()").value(1))
                .andExpect(jsonPath("$.groundingCheck.sentences[1].status").value("USER_INPUT"))
                .andExpect(jsonPath("$.groundingCheck.sentences[1].evidence.length()").value(0));

        reply = new FakeReply(200, """
                {"sentences": [{"text": "%s", "status": "OPINION", "evidence": [], "note": null}]}""".formatted(S5), 0);
        start(S5).andExpect(status().isAccepted()).andExpect(jsonPath("$.text").value(S5));
        String done = awaitDone();
        assertThat((String) JsonPath.read(done, "$.groundingCheck.text")).isEqualTo(S5);
        assertThat((List<?>) JsonPath.read(done, "$.groundingCheck.sentences")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from grounding_check", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from grounding_sentence", Integer.class)).isEqualTo(1);
    }

    @Test
    void 진행_중이면_409_이고_예전_것을_바꾸지_않는다() throws Exception {
        reply = new FakeReply(200, contractExample(), 700);
        start(TEXT).andExpect(status().isAccepted());

        expectError(start(S5), 409, "GROUNDING_CHECK_IN_PROGRESS");
        String done = awaitDone();
        assertThat((String) JsonPath.read(done, "$.groundingCheck.text")).isEqualTo(TEXT);
        assertThat((String) JsonPath.read(done, "$.groundingCheck.status")).isEqualTo("COMPLETED");
        assertThat(RECEIVED).hasSize(1);
        assertThat(jdbc.queryForObject("select count from analysis_usage where user_id = ?", Integer.class,
                TestLogin.USER_ID)).isEqualTo(1);
    }

    @Test
    void 한도를_다_썼으면_429_이고_예전_것을_바꾸지_않는다() throws Exception {
        jdbc.update("update app_user set daily_analysis_limit = 1 where id = ?", TestLogin.USER_ID);
        start(TEXT).andExpect(status().isAccepted());
        awaitDone();

        expectError(start(S5), 429, "DAILY_LIMIT_EXCEEDED");
        mvc.perform(get("/api/questions/{id}/grounding-check", questionId))
                .andExpect(jsonPath("$.groundingCheck.text").value(TEXT))
                .andExpect(jsonPath("$.groundingCheck.status").value("COMPLETED"))
                .andExpect(jsonPath("$.groundingCheck.sentences.length()").value(5));
    }

    // --- 실패 ---

    @Test
    void AI_가_오류를_주면_실패로_남고_같은_POST_로_다시_하면_된다() throws Exception {
        reply = new FakeReply(502, "{\"error\": {\"code\": \"LLM_ERROR\", \"message\": \"x\"}}", 0);
        start(TEXT).andExpect(status().isAccepted());
        String failed = awaitDone();
        assertThat((String) JsonPath.read(failed, "$.groundingCheck.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.groundingCheck.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.groundingCheck.error.message")).contains("LLM_ERROR");
        assertThat((List<?>) JsonPath.read(failed, "$.groundingCheck.sentences")).isEmpty();

        reply = new FakeReply(200, contractExample(), 0);
        start(TEXT).andExpect(status().isAccepted()).andExpect(jsonPath("$.error").value(nullValue()));
        assertThat((String) JsonPath.read(awaitDone(), "$.groundingCheck.status")).isEqualTo("COMPLETED");
    }

    @Test
    void 제한_시간을_넘기면_AI_TIMEOUT() throws Exception {
        reply = new FakeReply(200, contractExample(), 2_000);
        start(TEXT).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDone(), "$.groundingCheck.error.code")).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 계약을_어긴_응답이면_아무것도_저장하지_않고_실패() throws Exception {
        List<String> bad = List.of(
                "{\"sentences\": []}",
                // 보낸 글에 없는 문장
                "{\"sentences\": [{\"text\": \"지어낸 문장.\", \"status\": \"OPINION\", \"evidence\": [], \"note\": null}]}",
                // 목록 밖 판정
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"MAYBE\", \"evidence\": [], \"note\": null}]}".formatted(S5),
                // 보내지 않은 근거
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"SOURCE\", \"evidence\": [{\"type\": \"CITATION\", \"id\": 999999}], \"note\": null}]}"
                        .formatted(S1),
                // 원본에서 못 찾은 근거는 보내지 않았다
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"SOURCE\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}], \"note\": null}]}"
                        .formatted(S1, unmatchedCitation),
                // AI 가 뽑은 경험 본문은 근거가 아니다
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"USER_INPUT\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}], \"note\": null}]}"
                        .formatted(S1, chat),
                // USER_INPUT 에 원문
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"USER_INPUT\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}], \"note\": null}]}"
                        .formatted(S1, chatCitation),
                // 근거 없음인데 근거가 있다
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"UNSUPPORTED\", \"evidence\": [{\"type\": \"SUPPLEMENT\", \"id\": %d}], \"note\": \"x\"}]}"
                        .formatted(S1, chatSupplement),
                // 근거 없음인데 note 가 없다
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"UNSUPPORTED\", \"evidence\": [], \"note\": null}]}".formatted(S3),
                // 같은 근거 두 번
                "{\"sentences\": [{\"text\": \"%s\", \"status\": \"SOURCE\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}, {\"type\": \"CITATION\", \"id\": %d}], \"note\": null}]}"
                        .formatted(S1, chatCitation, chatCitation));
        for (String body : bad) {
            reply = new FakeReply(200, body, 0);
            start(TEXT).andExpect(status().isAccepted());
            String failed = awaitDone();
            assertThat((String) JsonPath.read(failed, "$.groundingCheck.status")).as(body).isEqualTo("FAILED");
            assertThat((String) JsonPath.read(failed, "$.groundingCheck.error.code")).as(body).isEqualTo("AI_FAILED");
            assertThat((String) JsonPath.read(failed, "$.groundingCheck.error.message")).as(body).startsWith("INVALID_RESPONSE");
        }
        assertThat(jdbc.queryForObject("select count(*) from grounding_sentence", Integer.class)).isZero();
    }

    @Test
    void 서버가_다시_켜지면_점검_중이던_것은_실패가_된다() {
        GroundingCheck check = new GroundingCheck(questionId);
        check.start("끊긴 점검");
        checks.save(check);

        cleaner.run(null);

        GroundingCheck after = checks.findByQuestionId(questionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    // --- 요청 오류 ---

    @Test
    void 시작_조회_오류() throws Exception {
        expectError(postJson("{\"text\": \"   \"}"), 400, "EMPTY_TEXT");
        expectError(postJson("{}"), 400, "INVALID_REQUEST");
        expectError(start("가".repeat(5001)), 413, "TEXT_TOO_LONG");
        expectError(mvc.perform(post("/api/questions/{id}/grounding-check", 999999)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"x\"}")), 404, "QUESTION_NOT_FOUND");
        expectError(mvc.perform(get("/api/questions/{id}/grounding-check", 999999)), 404, "QUESTION_NOT_FOUND");

        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            MockMvc otherMvc = TestLogin.mvc(context, other);
            expectError(otherMvc.perform(get("/api/questions/{id}/grounding-check", questionId)), 404, "QUESTION_NOT_FOUND");
            expectError(otherMvc.perform(post("/api/questions/{id}/grounding-check", questionId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"x\"}")), 404, "QUESTION_NOT_FOUND");
        } finally {
            jdbc.update("delete from app_user where id = ?", other);
        }
        assertThat(RECEIVED).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from grounding_check", Integer.class)).isZero();
    }

    private ResultActions start(String text) throws Exception {
        return postJson("{\"text\": \"" + text.replace("\"", "\\\"") + "\"}");
    }

    private ResultActions postJson(String json) throws Exception {
        return mvc.perform(post("/api/questions/{id}/grounding-check", questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // 점검은 다른 스레드에서 돈다 — 끝날 때까지 조회한다(FE 폴링과 같다)
    private String awaitDone() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/questions/{id}/grounding-check", questionId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
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

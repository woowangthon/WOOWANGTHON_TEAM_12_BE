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
import com.woowangthon.backend.job.JobStatus;
import com.woowangthon.backend.question.Answer;
import com.woowangthon.backend.question.AnswerRepository;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
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
 * ETC docs/contracts/question-api.md 「확정 답변」 과 ai-api.md 「POST /new-facts」(SPEC S-48).
 * AI 서버 자리에 가짜 HTTP 서버를 띄운다(테스트 전용). 찾기가 다른 스레드에서 돌아 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=1")
class AnswerApiTest {

    record FakeReply(int status, String body) {
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
        FAKE_AI.createContext("/new-facts", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            FakeReply r = reply;
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        FAKE_AI.start();
    }

    @DynamicPropertySource
    static void aiServer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + FAKE_AI.getAddress().getPort());
    }

    // 계약 예시의 글(문장 일곱)
    static final String S1 = "「바꿔요」 프로젝트에서 채팅 메시지가 늦게 도착하는 문제가 있었고, 제가 폴링을 WebSocket 으로 바꿔 해결했습니다.";
    static final String S2 = "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨습니다.";
    static final String S3 = "바꾼 뒤에는 두 기기로 메시지를 보내 양쪽 화면에 바로 도착하는지 확인했습니다.";
    static final String S4 = "이어서 알림 기능도 같은 방식으로 바꿔 서버 요청을 하루 1,200회 줄였습니다.";
    static final String S5 = "배포까지 제가 맡아 AWS 에 올렸습니다.";
    static final String S6 = "누구보다 끈질기게 문제를 파고들었습니다.";
    static final String S7 = "앞으로도 문제의 원인을 살피는 개발자가 되고 싶습니다.";
    static final String TEXT = String.join(" ", S1, S2, S3, S4, S5, S6, S7);

    static final String QUESTION_TEXT = "기술적인 문제를 해결한 경험을 구체적으로 적어 주세요.";

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
    ApplicationRepository applications;

    @Autowired
    QuestionRepository questions;

    @Autowired
    AnswerRepository answers;

    @Autowired
    InterruptedQuestionCleaner cleaner;

    MockMvc mvc;

    long appId;
    long studyId;
    long chat;
    long applicationId;
    long questionId;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        RECEIVED.clear();

        Source source = sources.save(new Source(TestLogin.USER_ID, SourceKind.FILE, "바꿔요-회고.md", "회고"));
        Activity app = activities.save(new Activity(TestLogin.USER_ID, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        Activity study = activities.save(new Activity(TestLogin.USER_ID, "알고리즘 스터디", new Period("2024-09", null, true)));
        appId = app.getId();
        studyId = study.getId();
        Experience e101 = experiences.save(new Experience(app, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING), CompetenciesBy.AI, Actor.ME, SourceStatus.SOURCE_CONFIRMED, 0));
        chat = e101.getId();
        citations.save(new Citation(e101, source, "내가 폴링을 WebSocket 으로 바꿔 해결했다.", true, 5, 5));
        citations.save(new Citation(e101, source, "원본에서 못 찾은 발췌", false, null, null));

        Application application = applications.save(new Application(TestLogin.USER_ID, "OO 백엔드 부트캠프"));
        applicationId = application.getId();
        // 추천은 돌리지 않는다 — 확정은 문항의 추천 상태와 상관없다
        questionId = questions.save(new Question(application, QUESTION_TEXT)).getId();

        // 저장된 초안 — 이 문장들은 후보로 삼지 않는다
        Long draftId = jdbc.queryForObject(
                "insert into draft (question_id, status) values (?, 'COMPLETED') returning id", Long.class, questionId);
        jdbc.update("insert into draft_sentence (draft_id, position, text, kind) values (?, 0, ?, 'FACT'), (?, 1, ?, 'FACT'), (?, 2, ?, 'OPINION')",
                draftId, S1, draftId, S2, draftId, S7);

        reply = new FakeReply(200, contractExample());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table new_fact_candidate, answer_dismissed, answer, draft_sentence_evidence, draft_sentence, "
                + "draft_omitted, draft_guideline, draft_experience, draft, grounding_sentence_evidence, grounding_sentence, grounding_check, question_recommendation_evidence, question_recommendation, "
                + "question_requirement, supplement, clarification, question, application, analysis_usage, citation, "
                + "experience, activity_link, activity, source_section, source cascade");
    }

    private String contractExample() {
        return """
                {
                  "candidates": [
                    { "sentence": "%s", "content": "WebSocket 으로 바꾼 뒤 두 기기로 메시지를 보내 양쪽 화면에 바로 도착하는지 확인했다", "actor": "ME", "activityId": %d },
                    { "sentence": "%s", "content": "알림 기능도 구독 방식으로 바꿔 서버 요청을 하루 1,200회 줄였다", "actor": "ME", "activityId": %d }
                  ],
                  "model": "test-model"
                }
                """.formatted(S3, appId, S4, appId);
    }

    // --- 계약 예시 흐름 ---

    @Test
    void 확정_찾기_저장_저장안함_다시확정_다시찾기() throws Exception {
        detail().andExpect(jsonPath("$.answer").value(nullValue()));

        // 확정 — AI 를 부르지 않고 분석 횟수도 쓰지 않는다
        confirm(TEXT).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer.questionId").value(questionId))
                .andExpect(jsonPath("$.answer.text").value(TEXT))
                .andExpect(jsonPath("$.answer.confirmedAt").isString())
                .andExpect(jsonPath("$.answer.newFacts").value(nullValue()));
        assertThat(used()).isZero();
        assertThat(RECEIVED).isEmpty();

        // 찾기 — 202 · ANALYZING · 분석 1회
        find().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.answer.newFacts.status").value("ANALYZING"))
                .andExpect(jsonPath("$.answer.newFacts.error").value(nullValue()))
                .andExpect(jsonPath("$.answer.newFacts.candidates.length()").value(0));
        String done = awaitDone();
        assertThat(used()).isEqualTo(1);
        assertThat((String) JsonPath.read(done, "$.answer.newFacts.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(done, "$.answer.newFacts.createdAt")).matches(".*\\+09:00");

        // AI 에는 확정 답 · 경험 전부(원본에서 찾은 근거만, origin 없음) · 활동 전부 · 초안 문장
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.text")).isEqualTo(TEXT);
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[*].id")).containsExactly((int) chat);
        assertThat((List<String>) JsonPath.read(sent, "$.experiences[0].citations[*].quote"))
                .containsExactly("내가 폴링을 WebSocket 으로 바꿔 해결했다.");
        assertThat(sent).doesNotContain("origin");
        assertThat((List<Integer>) JsonPath.read(sent, "$.activities[*].id")).containsExactly((int) studyId, (int) appId);
        assertThat((String) JsonPath.read(sent, "$.activities[1].name")).isEqualTo("바꿔요 — 캠퍼스 중고거래 앱");
        assertThat((List<String>) JsonPath.read(sent, "$.excludedSentences")).containsExactly(S1, S2, S7);

        List<Integer> ids = JsonPath.read(done, "$.answer.newFacts.candidates[*].id");
        assertThat(ids).hasSize(2);
        long saveId = ids.get(0);
        long dismissId = ids.get(1);
        detail().andExpect(jsonPath("$.answer.newFacts.candidates[0].sentence").value(S3))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].actor").value("ME"))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].activityId").value(appId))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].activityName").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].status").value("PENDING"))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].experienceId").value(nullValue()))
                .andExpect(jsonPath("$.answer.newFacts.candidates[1].sentence").value(S4));

        // 경험으로 저장 — 사용자가 고친 값 · 직접 추가와 같은 경험 + origin
        String edited = "WebSocket 으로 바꾼 뒤 두 기기로 메시지가 바로 도착하는지 확인했다";
        String created = save(saveId, "{\"activityId\": " + appId + ", \"content\": \"" + edited
                + "\", \"actor\": \"ME\", \"competencies\": [\"IMPLEMENTATION\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value(appId))
                .andExpect(jsonPath("$.content").value(edited))
                .andExpect(jsonPath("$.competencies[0]").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.competenciesBy").value("USER"))
                .andExpect(jsonPath("$.actor").value("ME"))
                .andExpect(jsonPath("$.sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.citations.length()").value(0))
                .andExpect(jsonPath("$.origin.type").value("ANSWER"))
                .andExpect(jsonPath("$.origin.questionId").value(questionId))
                .andExpect(jsonPath("$.origin.applicationId").value(applicationId))
                .andExpect(jsonPath("$.origin.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.origin.questionText").value(QUESTION_TEXT))
                .andReturn().getResponse().getContentAsString();
        long saved = ((Number) JsonPath.read(created, "$.id")).longValue();
        // 두 번 저장해도 경험을 또 만들지 않는다
        expectError(save(saveId, "{\"activityId\": " + appId + ", \"content\": \"또\", \"actor\": \"ME\"}"), 409,
                "NEW_FACT_ALREADY_DECIDED");
        expectError(mvc.perform(post("/api/new-fact-candidates/{id}/dismiss", saveId)), 409, "NEW_FACT_ALREADY_DECIDED");
        assertThat(experiences.count()).isEqualTo(2);

        // 저장 안 함
        mvc.perform(post("/api/new-fact-candidates/{id}/dismiss", dismissId)).andExpect(status().isNoContent());
        expectError(mvc.perform(post("/api/new-fact-candidates/{id}/dismiss", dismissId)), 409, "NEW_FACT_ALREADY_DECIDED");

        detail().andExpect(jsonPath("$.answer.newFacts.candidates[0].status").value("SAVED"))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].experienceId").value(saved))
                .andExpect(jsonPath("$.answer.newFacts.candidates[1].status").value("DISMISSED"))
                .andExpect(jsonPath("$.answer.newFacts.candidates[1].experienceId").value(nullValue()));

        // 다른 Experience 응답에도 origin 이 실린다 — 보통 경험은 null
        mvc.perform(get("/api/activities/{id}", appId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences[0].origin").value(nullValue()))
                .andExpect(jsonPath("$.experiences[1].id").value(saved))
                .andExpect(jsonPath("$.experiences[1].origin.type").value("ANSWER"));
        // 고쳐도 origin 은 그대로
        mvc.perform(patch("/api/experiences/{id}", saved).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"고친 문장\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin.questionId").value(questionId));

        // 다시 확정 — 후보는 사라지고 만든 경험은 남는다
        confirm(TEXT).andExpect(status().isOk()).andExpect(jsonPath("$.answer.newFacts").value(nullValue()));
        expectError(save(saveId, "{\"activityId\": " + appId + ", \"content\": \"또\", \"actor\": \"ME\"}"), 404,
                "NEW_FACT_NOT_FOUND");
        assertThat(experiences.existsById(saved)).isTrue();

        // 다시 찾기 — 저장 안 함 한 문장은 뺀다
        RECEIVED.clear();
        find().andExpect(status().isAccepted());
        awaitDone();
        assertThat((List<String>) JsonPath.read(RECEIVED.getFirst(), "$.excludedSentences")).containsExactly(S1, S2, S7, S4);
        // 저장한 경험은 이제 경험으로 AI 에 간다(origin 없이)
        assertThat((List<Integer>) JsonPath.read(RECEIVED.getFirst(), "$.experiences[*].id"))
                .containsExactlyInAnyOrder((int) chat, (int) saved);
        assertThat(used()).isEqualTo(2);
    }

    @Test
    void 저장한_경험을_지우면_experienceId_만_null_활동이_사라지면_활동도_null() throws Exception {
        confirm(TEXT).andExpect(status().isOk());
        find().andExpect(status().isAccepted());
        List<Integer> ids = JsonPath.read(awaitDone(), "$.answer.newFacts.candidates[*].id");
        String created = save(ids.get(0), "{\"activityId\": " + studyId + ", \"content\": \"확인했다\", \"actor\": \"ME\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long saved = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(delete("/api/experiences/{id}", saved)).andExpect(status().isNoContent());
        jdbc.update("delete from citation where experience_id in (select id from experience where activity_id = ?)", appId);
        jdbc.update("delete from experience where activity_id = ?", appId);
        jdbc.update("delete from activity where id = ?", appId);

        detail().andExpect(jsonPath("$.answer.newFacts.candidates[0].status").value("SAVED"))
                .andExpect(jsonPath("$.answer.newFacts.candidates[0].experienceId").value(nullValue()))
                .andExpect(jsonPath("$.answer.newFacts.candidates[1].activityId").value(nullValue()))
                .andExpect(jsonPath("$.answer.newFacts.candidates[1].activityName").value(nullValue()));
    }

    // --- 실패 ---

    @Test
    void AI_가_실패하면_FAILED_이고_다시_찾을_수_있다() throws Exception {
        confirm(TEXT).andExpect(status().isOk());
        reply = new FakeReply(502, "{\"error\": {\"code\": \"LLM_ERROR\", \"message\": \"x\"}}");
        find().andExpect(status().isAccepted());
        String failed = awaitDone();
        assertThat((String) JsonPath.read(failed, "$.answer.newFacts.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.answer.newFacts.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.answer.newFacts.error.message")).startsWith("LLM_ERROR");
        assertThat((List<?>) JsonPath.read(failed, "$.answer.newFacts.candidates")).isEmpty();

        reply = new FakeReply(200, contractExample());
        find().andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDone(), "$.answer.newFacts.status")).isEqualTo("COMPLETED");
    }

    @Test
    void 계약을_벗어난_AI_응답은_고치지_않고_실패() throws Exception {
        confirm(TEXT).andExpect(status().isOk());
        for (String body : List.of(
                // 보내지 않은 활동
                "{\"candidates\": [{\"sentence\": \"" + S3 + "\", \"content\": \"확인했다\", \"actor\": \"ME\", \"activityId\": 999999}]}",
                // 빼야 할 문장
                "{\"candidates\": [{\"sentence\": \"" + S1 + "\", \"content\": \"해결했다\", \"actor\": \"ME\", \"activityId\": null}]}",
                // 글에 없는 문장
                "{\"candidates\": [{\"sentence\": \"지어낸 문장입니다.\", \"content\": \"했다\", \"actor\": \"ME\", \"activityId\": null}]}",
                // 순서가 뒤바뀜
                "{\"candidates\": [{\"sentence\": \"" + S4 + "\", \"content\": \"줄였다\", \"actor\": \"ME\", \"activityId\": null},"
                        + "{\"sentence\": \"" + S3 + "\", \"content\": \"확인했다\", \"actor\": \"ME\", \"activityId\": null}]}",
                // 틀린 actor
                "{\"candidates\": [{\"sentence\": \"" + S3 + "\", \"content\": \"확인했다\", \"actor\": \"I\", \"activityId\": null}]}",
                "{\"candidates\": null}")) {
            reply = new FakeReply(200, body);
            find().andExpect(status().isAccepted());
            String failed = awaitDone();
            assertThat((String) JsonPath.read(failed, "$.answer.newFacts.status")).as(body).isEqualTo("FAILED");
            assertThat((String) JsonPath.read(failed, "$.answer.newFacts.error.code")).as(body).isEqualTo("AI_FAILED");
        }
        assertThat(jdbc.queryForObject("select count(*) from new_fact_candidate", Integer.class)).isZero();
    }

    @Test
    void 후보가_없으면_빈_목록() throws Exception {
        reply = new FakeReply(200, "{\"candidates\": []}");
        confirm(TEXT).andExpect(status().isOk());
        find().andExpect(status().isAccepted());
        String done = awaitDone();
        assertThat((String) JsonPath.read(done, "$.answer.newFacts.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.answer.newFacts.candidates")).isEmpty();
    }

    @Test
    void 찾는_중이면_확정도_찾기도_409_이고_예전_답은_그대로() throws Exception {
        Answer answer = new Answer(questionId);
        answer.confirm("예전 답");
        answer.startFinding();
        answers.save(answer);

        expectError(confirm(TEXT), 409, "NEW_FACTS_IN_PROGRESS");
        expectError(find(), 409, "NEW_FACTS_IN_PROGRESS");
        assertThat(answers.findByQuestionId(questionId).orElseThrow().getText()).isEqualTo("예전 답");
        assertThat(used()).isZero();
    }

    @Test
    void 서버가_다시_켜지면_찾던_것은_실패가_된다() {
        Answer answer = new Answer(questionId);
        answer.confirm(TEXT);
        answer.startFinding();
        answers.save(answer);

        cleaner.run(null);

        Answer after = answers.findByQuestionId(questionId).orElseThrow();
        assertThat(after.getNewFactsStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    // --- 요청 오류 ---

    @Test
    void 확정_오류() throws Exception {
        expectError(putJson(questionId, "{}"), 400, "INVALID_REQUEST");
        expectError(putJson(questionId, "{\"text\": \"   \"}"), 400, "EMPTY_TEXT");
        expectError(confirm("가".repeat(5001)), 413, "TEXT_TOO_LONG");
        expectError(putJson(999999, "{\"text\": \"답\"}"), 404, "QUESTION_NOT_FOUND");
        confirm("가".repeat(5000)).andExpect(status().isOk());
    }

    @Test
    void 찾기_오류() throws Exception {
        expectError(find(), 404, "ANSWER_NOT_FOUND");
        expectError(mvc.perform(post("/api/questions/{id}/answer/new-facts", 999999)), 404, "QUESTION_NOT_FOUND");

        // 한도를 다 써도 답은 확정되고, 찾기만 429 — 예전 찾기를 바꾸지 않는다
        jdbc.update("update app_user set daily_analysis_limit = 0 where id = ?", TestLogin.USER_ID);
        confirm(TEXT).andExpect(status().isOk());
        expectError(find(), 429, "DAILY_LIMIT_EXCEEDED");
        detail().andExpect(jsonPath("$.answer.text").value(TEXT))
                .andExpect(jsonPath("$.answer.newFacts").value(nullValue()));
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 저장_오류() throws Exception {
        confirm(TEXT).andExpect(status().isOk());
        find().andExpect(status().isAccepted());
        long id = ((Number) JsonPath.<List<Integer>>read(awaitDone(), "$.answer.newFacts.candidates[*].id").get(0)).longValue();

        expectError(save(id, "{\"content\": \"했다\", \"actor\": \"ME\"}"), 400, "INVALID_REQUEST");
        expectError(save(id, "{\"activityId\": " + appId + ", \"content\": \" \", \"actor\": \"ME\"}"), 400, "INVALID_REQUEST");
        expectError(save(id, "{\"activityId\": " + appId + ", \"content\": \"했다\", \"actor\": \"I\"}"), 400, "INVALID_REQUEST");
        expectError(save(id, "{\"activityId\": " + appId + ", \"content\": \"했다\", \"actor\": \"ME\", \"competencies\": [\"X\"]}"),
                400, "INVALID_REQUEST");
        expectError(save(id, "{\"activityId\": 999999, \"content\": \"했다\", \"actor\": \"ME\"}"), 404, "ACTIVITY_NOT_FOUND");
        expectError(save(999999, "{\"activityId\": " + appId + ", \"content\": \"했다\", \"actor\": \"ME\"}"), 404,
                "NEW_FACT_NOT_FOUND");
        expectError(mvc.perform(post("/api/new-fact-candidates/{id}/dismiss", 999999)), 404, "NEW_FACT_NOT_FOUND");
        // 실패한 저장은 아무것도 남기지 않는다
        assertThat(experiences.count()).isEqualTo(1);
        detail().andExpect(jsonPath("$.answer.newFacts.candidates[0].status").value("PENDING"));
    }

    // --- 도우미 ---

    private ResultActions detail() throws Exception {
        return mvc.perform(get("/api/questions/{id}", questionId)).andExpect(status().isOk());
    }

    private ResultActions confirm(String text) throws Exception {
        return putJson(questionId, "{\"text\": \"" + text.replace("\"", "\\\"") + "\"}");
    }

    private ResultActions putJson(long id, String json) throws Exception {
        return mvc.perform(put("/api/questions/{id}/answer", id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions find() throws Exception {
        return mvc.perform(post("/api/questions/{id}/answer/new-facts", questionId));
    }

    private ResultActions save(long candidateId, String json) throws Exception {
        return mvc.perform(post("/api/new-fact-candidates/{id}/save", candidateId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int used() {
        List<Integer> counts = jdbc.queryForList("select count from analysis_usage where user_id = ?", Integer.class,
                TestLogin.USER_ID);
        return counts.isEmpty() ? 0 : counts.getFirst();
    }

    // 찾기는 다른 스레드에서 돈다 — 끝날 때까지 문항을 조회한다(FE 폴링과 같다)
    private String awaitDone() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = detail().andReturn().getResponse().getContentAsString();
            if (!"ANALYZING".equals(JsonPath.read(body, "$.answer.newFacts.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("문항 " + questionId + " 의 새 경험 후보 찾기가 15초 안에 끝나지 않았습니다");
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

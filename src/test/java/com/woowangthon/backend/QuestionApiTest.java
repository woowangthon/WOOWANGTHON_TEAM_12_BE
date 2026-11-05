package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.woowangthon.backend.job.JobStatus;
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
 * ETC docs/contracts/question-api.md 「문항 넣기」 · 「목록」 · 「문항 하나」 · 「다시 시도」 · 「되묻기에 답하기」 와
 * ai-api.md 「POST /match」.
 * AI 서버 자리에 가짜 HTTP 서버를 띄워 응답 · 오류를 돌려준다(테스트 전용 — 앱은 진짜 AI 서버만 부른다).
 * 추천이 다른 스레드에서 돌아 트랜잭션 롤백을 못 쓰므로 테스트마다 표를 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ai.timeout-seconds=1")
class QuestionApiTest {

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
        FAKE_AI.createContext("/match", exchange -> {
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

    static final String QUESTION_A = """
            {"organization": "OO 백엔드 부트캠프", "text": "기술적인 문제를 해결한 경험을 구체적으로 적어 주세요."}""";

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
    QuestionRepository questions;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    InterruptedQuestionCleaner cleaner;

    MockMvc mvc;

    // 계약 예시의 「바꿔요」 경험 101(ME) · 102(TEAM) 과 근거 201 · 202, 다른 활동의 경험 105
    long appId;
    long studyId;
    long chat;
    long retro;
    long study;
    long chatCitation;
    long retroCitation;

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
                List.of(Competency.PROBLEM_SOLVING, Competency.IMPLEMENTATION), CompetenciesBy.AI, Actor.ME,
                SourceStatus.SOURCE_CONFIRMED, 0));
        Experience e102 = experiences.save(new Experience(app, "매주 금요일 스프린트 회고를 했다",
                List.of(Competency.COLLABORATION), CompetenciesBy.AI, Actor.TEAM, SourceStatus.SOURCE_CONFIRMED, 1));
        Experience e105 = experiences.save(new Experience(studyGroup, "매주 풀 문제를 고른다",
                List.of(Competency.OPERATION), CompetenciesBy.AI, Actor.ME, SourceStatus.NEEDS_REVIEW, 0));
        chat = e101.getId();
        retro = e102.getId();
        study = e105.getId();
        chatCitation = citations.save(new Citation(e101, source,
                "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.", true, 5, 5)).getId();
        retroCitation = citations.save(new Citation(e102, source, "팀은 매주 금요일 스프린트 회고를 했다.", true, 6, 6))
                .getId();
        reply = new FakeReply(200, contractExample(), 0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table question_recommendation_evidence, question_recommendation, question_requirement, "
                + "supplement, clarification, question, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity, source_section, source");
    }

    // ai-api.md 응답 예시(문항 A 모양) — ID 는 이 테스트가 만든 경험 · 근거
    private String contractExample() {
        return """
                {
                  "requirements": [
                    { "text": "기술 문제의 원인을 찾아 해결한 경험", "status": "SUPPORTED", "experienceIds": [%1$d] },
                    { "text": "팀과 함께 일한 방식", "status": "PARTIAL", "experienceIds": [%2$d, %1$d] },
                    { "text": "Java · Spring 사용 경험", "status": "NOT_FOUND", "experienceIds": [] }
                  ],
                  "recommendations": [
                    {
                      "experienceId": %1$d,
                      "reason": "채팅 지연이라는 기술 문제를 직접 해결한 사례라 문항과 맞는다",
                      "emphasis": "폴링의 한계를 보고 WebSocket 으로 바꾼 판단",
                      "evidence": [{ "type": "CITATION", "id": %3$d }],
                      "gaps": ["직접 고친 범위가 자료에 없다", "고친 뒤 확인한 방법이 자료에 없다"]
                    },
                    {
                      "experienceId": %2$d,
                      "reason": "팀이 매주 회고로 일하는 방식을 맞춘 사례다",
                      "emphasis": "정기 회고로 진행을 함께 점검한 방식",
                      "evidence": [{ "type": "CITATION", "id": %4$d }],
                      "gaps": []
                    }
                  ],
                  "clarifications": [
                    { "experienceId": %1$d, "question": "폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?" },
                    { "experienceId": %2$d, "question": "매주 회고에서 직접 맡은 일이 있었다면 무엇이었나요?" }
                  ],
                  "model": "test-model"
                }
                """.formatted(chat, retro, chatCitation, retroCitation);
    }

    // --- 문항 넣기 → 완료 ---

    @Test
    void 문항을_넣으면_추천되고_요구사항_추천_되묻기가_저장된다() throws Exception {
        String submitted = postQuestion(QUESTION_A)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.applicationId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long questionId = ((Number) JsonPath.read(submitted, "$.questionId")).longValue();
        long applicationId = ((Number) JsonPath.read(submitted, "$.applicationId")).longValue();

        String done = awaitDone(questionId);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(done, "$.error")).isNull();
        assertThat((String) JsonPath.read(done, "$.createdAt")).matches(".*\\+09:00");

        // AI 에는 그 사용자의 경험을 전부 싣는다(ADR-003) — 근거는 { id, quote } 만, 보완은 아직 []
        assertThat(RECEIVED).hasSize(1);
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.question.organization")).isEqualTo("OO 백엔드 부트캠프");
        assertThat((String) JsonPath.read(sent, "$.question.text")).startsWith("기술적인 문제를");
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[*].id"))
                .containsExactly((int) study, (int) chat, (int) retro); // 타임라인 순서
        assertThat((String) JsonPath.read(sent, "$.experiences[1].activityName")).isEqualTo("바꿔요 — 캠퍼스 중고거래 앱");
        assertThat((String) JsonPath.read(sent, "$.experiences[1].period.start")).isEqualTo("2025-03");
        assertThat((String) JsonPath.read(sent, "$.experiences[1].actor")).isEqualTo("ME");
        assertThat((String) JsonPath.read(sent, "$.experiences[1].sourceStatus")).isEqualTo("SOURCE_CONFIRMED");
        assertThat((List<String>) JsonPath.read(sent, "$.experiences[1].competencies"))
                .containsExactly("PROBLEM_SOLVING", "IMPLEMENTATION");
        assertThat(((Number) JsonPath.read(sent, "$.experiences[1].citations[0].id")).longValue()).isEqualTo(chatCitation);
        assertThat((String) JsonPath.read(sent, "$.experiences[1].citations[0].quote")).startsWith("채팅 메시지가");
        assertThat((List<?>) JsonPath.read(sent, "$.experiences[0].citations")).isEmpty();
        assertThat((List<?>) JsonPath.read(sent, "$.experiences[1].supplements")).isEmpty();

        // 새로고침 — 다시 불러도 그대로(S-21)
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(questionId))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.requirements.length()").value(3))
                .andExpect(jsonPath("$.requirements[0].status").value("SUPPORTED"))
                .andExpect(jsonPath("$.requirements[0].experienceIds[0]").value(chat))
                .andExpect(jsonPath("$.requirements[1].experienceIds.length()").value(2))
                .andExpect(jsonPath("$.requirements[2].status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requirements[2].experienceIds.length()").value(0))
                .andExpect(jsonPath("$.recommendations.length()").value(2))
                .andExpect(jsonPath("$.recommendations[0].activity.id").value(appId))
                .andExpect(jsonPath("$.recommendations[0].activity.name").value("바꿔요 — 캠퍼스 중고거래 앱"))
                .andExpect(jsonPath("$.recommendations[0].activity.period.end").value("2025-06"))
                .andExpect(jsonPath("$.recommendations[0].experience.id").value(chat))
                .andExpect(jsonPath("$.recommendations[0].experience.activityId").value(appId))
                .andExpect(jsonPath("$.recommendations[0].experience.citations[0].sourceName").value("바꿔요-회고.md"))
                .andExpect(jsonPath("$.recommendations[0].reason").value("채팅 지연이라는 기술 문제를 직접 해결한 사례라 문항과 맞는다"))
                .andExpect(jsonPath("$.recommendations[0].emphasis").value("폴링의 한계를 보고 WebSocket 으로 바꾼 판단"))
                .andExpect(jsonPath("$.recommendations[0].evidence[0].type").value("CITATION"))
                .andExpect(jsonPath("$.recommendations[0].evidence[0].id").value(chatCitation))
                .andExpect(jsonPath("$.recommendations[0].gaps.length()").value(2))
                .andExpect(jsonPath("$.recommendations[1].experience.id").value(retro))
                .andExpect(jsonPath("$.recommendations[1].experience.actor").value("TEAM"))
                .andExpect(jsonPath("$.recommendations[1].gaps.length()").value(0))
                .andExpect(jsonPath("$.clarifications.length()").value(2))
                .andExpect(jsonPath("$.clarifications[0].id").isNumber())
                .andExpect(jsonPath("$.clarifications[0].experienceId").value(chat))
                .andExpect(jsonPath("$.clarifications[0].question").value("폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?"))
                .andExpect(jsonPath("$.clarifications[0].supplementId").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void 경험이_없어도_받고_AI_에_빈_후보를_보낸다() throws Exception {
        jdbc.execute("truncate table supplement, citation, experience, activity");
        reply = new FakeReply(200, """
                {"requirements": [{"text": "기술 문제를 해결한 경험", "status": "NOT_FOUND", "experienceIds": []}],
                 "recommendations": [], "clarifications": []}""", 0);
        String done = awaitDone(submit("""
                {"organization": null, "text": "기술적인 문제를 해결한 경험을 적어 주세요."}"""));

        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(done, "$.organization")).isNull();
        assertThat((List<?>) JsonPath.read(done, "$.recommendations")).isEmpty();
        assertThat((List<?>) JsonPath.read(RECEIVED.getFirst(), "$.experiences")).isEmpty();
        assertThat((Object) JsonPath.read(RECEIVED.getFirst(), "$.question.organization")).isNull();
    }

    @Test
    void 목록은_최근에_넣은_것부터() throws Exception {
        long first = submit(QUESTION_A);
        awaitDone(first);
        long second = submit("""
                {"organization": "  ", "text": "팀 프로젝트에서 맡은 역할을 적어 주세요."}""");
        awaitDone(second);

        mvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0].id").value(second))
                .andExpect(jsonPath("$.questions[0].organization").value(nullValue()))
                .andExpect(jsonPath("$.questions[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.questions[0].createdAt").isString())
                .andExpect(jsonPath("$.questions[1].id").value(first))
                .andExpect(jsonPath("$.questions[1].text").value("기술적인 문제를 해결한 경험을 구체적으로 적어 주세요."));
    }

    @Test
    void 추천한_경험은_지금_상태로_싣고_지워졌으면_뺀다() throws Exception {
        long questionId = submit(QUESTION_A);
        awaitDone(questionId);

        // 문항을 넣은 뒤 고친 내용이 보인다
        mvc.perform(patch("/api/experiences/{id}", chat).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"채팅을 WebSocket 으로 바꿨다\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.recommendations[0].experience.content").value("채팅을 WebSocket 으로 바꿨다"))
                .andExpect(jsonPath("$.recommendations[0].experience.sourceStatus").value("USER_ENTERED"));

        // 지운 경험의 추천 · 되묻기 · 요구사항 ID 는 빠지고, 문항 조회는 깨지지 않는다
        mvc.perform(delete("/api/experiences/{id}", chat)).andExpect(status().isNoContent());
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements.length()").value(3))
                .andExpect(jsonPath("$.requirements[0].experienceIds.length()").value(0))
                .andExpect(jsonPath("$.requirements[1].experienceIds.length()").value(1))
                .andExpect(jsonPath("$.requirements[1].experienceIds[0]").value(retro))
                .andExpect(jsonPath("$.recommendations.length()").value(1))
                .andExpect(jsonPath("$.recommendations[0].experience.id").value(retro))
                .andExpect(jsonPath("$.clarifications.length()").value(1))
                .andExpect(jsonPath("$.clarifications[0].experienceId").value(retro));
    }

    // --- 실패 → 다시 시도 ---

    @Test
    void AI_가_오류를_주면_실패와_이유가_남고_다시_시도하면_된다() throws Exception {
        reply = new FakeReply(413, """
                {"error": {"code": "TEXT_TOO_LONG", "message": "too long"}}""", 0);
        long questionId = submit(QUESTION_A);
        String failed = awaitDone(questionId);
        assertThat((String) JsonPath.read(failed, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_FAILED");
        assertThat((String) JsonPath.read(failed, "$.error.message")).contains("TEXT_TOO_LONG");
        assertThat((List<?>) JsonPath.read(failed, "$.requirements")).isEmpty();
        assertThat((List<?>) JsonPath.read(failed, "$.recommendations")).isEmpty();
        assertThat((List<?>) JsonPath.read(failed, "$.clarifications")).isEmpty();

        reply = new FakeReply(200, contractExample(), 0);
        mvc.perform(post("/api/questions/{id}/retry", questionId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(questionId))
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.recommendations.length()").value(0));

        String done = awaitDone(questionId);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.recommendations")).hasSize(2);
        assertThat(questions.count()).isEqualTo(1);
        assertThat(RECEIVED).hasSize(2);
    }

    @Test
    void 제한_시간을_넘기면_AI_TIMEOUT() throws Exception {
        reply = new FakeReply(200, contractExample(), 2_500);
        String failed = awaitDone(submit(QUESTION_A));
        assertThat((String) JsonPath.read(failed, "$.error.code")).isEqualTo("AI_TIMEOUT");
        assertThat((List<?>) JsonPath.read(failed, "$.recommendations")).isEmpty();
    }

    @Test
    void 계약을_어긴_응답이면_아무것도_저장하지_않고_실패() throws Exception {
        String ok = "{\"text\": \"요구\", \"status\": \"SUPPORTED\", \"experienceIds\": [%d]}".formatted(chat);
        List<String> broken = List.of(
                // 보내지 않은 경험을 추천
                """
                {"requirements": [%s], "recommendations": [{"experienceId": 999999, "reason": "r", "emphasis": "e",
                 "evidence": [], "gaps": []}], "clarifications": []}""".formatted(ok),
                // 다른 경험의 근거를 근거로
                """
                {"requirements": [%s], "recommendations": [{"experienceId": %d, "reason": "r", "emphasis": "e",
                 "evidence": [{"type": "CITATION", "id": %d}], "gaps": []}], "clarifications": []}"""
                        .formatted(ok, chat, retroCitation),
                // 아직 없는 보완을 근거로
                """
                {"requirements": [%s], "recommendations": [{"experienceId": %d, "reason": "r", "emphasis": "e",
                 "evidence": [{"type": "SUPPLEMENT", "id": 1}], "gaps": []}], "clarifications": []}"""
                        .formatted(ok, chat),
                // 추천하지 않은 경험에 되묻기
                """
                {"requirements": [%s], "recommendations": [], "clarifications": [{"experienceId": %d, "question": "q"}]}"""
                        .formatted(ok, study),
                // 같은 경험을 두 번 추천
                """
                {"requirements": [%1$s], "recommendations": [
                 {"experienceId": %2$d, "reason": "r", "emphasis": "e", "evidence": [], "gaps": []},
                 {"experienceId": %2$d, "reason": "r", "emphasis": "e", "evidence": [], "gaps": []}], "clarifications": []}"""
                        .formatted(ok, chat),
                // 요구사항 0개
                """
                {"requirements": [], "recommendations": [], "clarifications": []}""",
                // NOT_FOUND 인데 경험이 있다
                """
                {"requirements": [{"text": "요구", "status": "NOT_FOUND", "experienceIds": [%d]}],
                 "recommendations": [], "clarifications": []}""".formatted(chat),
                // 목록 밖 판정
                """
                {"requirements": [{"text": "요구", "status": "MAYBE", "experienceIds": []}],
                 "recommendations": [], "clarifications": []}""",
                // 빈 이유
                """
                {"requirements": [%s], "recommendations": [{"experienceId": %d, "reason": " ", "emphasis": "e",
                 "evidence": [], "gaps": []}], "clarifications": []}""".formatted(ok, chat),
                // 목록 밖 문항 유형(S-41)
                withType(contractExample(), "HOBBY"),
                "not json");

        for (String body : broken) {
            reply = new FakeReply(200, body, 0);
            String failed = awaitDone(submit(QUESTION_A));
            assertThat((String) JsonPath.read(failed, "$.status")).as(body).isEqualTo("FAILED");
            assertThat((String) JsonPath.read(failed, "$.error.code")).as(body).isEqualTo("AI_FAILED");
            assertThat((String) JsonPath.read(failed, "$.error.message")).as(body).startsWith("INVALID_RESPONSE");
            assertThat((List<?>) JsonPath.read(failed, "$.requirements")).as(body).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from question_requirement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from question_recommendation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isZero();
    }

    @Test
    void 서버가_다시_켜지면_분석_중이던_문항은_실패가_된다() {
        Question question = questions.save(new Question(applications.save(new Application(TestLogin.USER_ID, null)), "끊긴 문항"));

        cleaner.run(null);

        Question after = questions.findById(question.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    // --- 되묻기에 답하기 → 보완 (S-23 · S-25) ---

    static final String CHAT_ANSWER = "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다";

    @Test
    void 되묻기에_답하면_그_경험의_보완이_되고_본문_근거_출처는_그대로다() throws Exception {
        long questionId = submit(QUESTION_A);
        String done = awaitDone(questionId);
        long first = ((Number) JsonPath.read(done, "$.clarifications[0].id")).longValue();

        String answered = answer(first, CHAT_ANSWER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.experienceId").value(chat))
                .andExpect(jsonPath("$.supplement.id").isNumber())
                .andExpect(jsonPath("$.supplement.question").value("폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?"))
                .andExpect(jsonPath("$.supplement.answer").value(CHAT_ANSWER))
                .andExpect(jsonPath("$.supplement.questionId").value(questionId))
                .andReturn().getResponse().getContentAsString();
        long supplementId = ((Number) JsonPath.read(answered, "$.supplement.id")).longValue();
        assertThat((String) JsonPath.read(answered, "$.supplement.createdAt")).matches(".*\\+09:00");
        assertThat((String) JsonPath.read(answered, "$.supplement.updatedAt")).matches(".*\\+09:00");

        // 활동 세부(S-08)의 그 경험에 보완이 실리고, 본문 · 근거 · 출처 상태 · 역량 출처는 그대로다
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences[0].id").value(chat))
                .andExpect(jsonPath("$.experiences[0].content").value("채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다"))
                .andExpect(jsonPath("$.experiences[0].sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.experiences[0].competenciesBy").value("AI"))
                .andExpect(jsonPath("$.experiences[0].citations.length()").value(1))
                .andExpect(jsonPath("$.experiences[0].citations[0].id").value(chatCitation))
                .andExpect(jsonPath("$.experiences[0].supplements.length()").value(1))
                .andExpect(jsonPath("$.experiences[0].supplements[0].id").value(supplementId))
                .andExpect(jsonPath("$.experiences[0].supplements[0].answer").value(CHAT_ANSWER))
                .andExpect(jsonPath("$.experiences[0].supplements[0].questionId").value(questionId))
                .andExpect(jsonPath("$.experiences[1].supplements.length()").value(0));

        // 문항에선 답한 되묻기에 보완 ID 가, 건너뛴 되묻기엔 null 이 남는다
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.clarifications[0].supplementId").value(supplementId))
                .andExpect(jsonPath("$.clarifications[1].supplementId").value(nullValue()))
                .andExpect(jsonPath("$.recommendations[0].experience.supplements[0].id").value(supplementId))
                .andExpect(jsonPath("$.recommendations[0].evidence.length()").value(1));

        // 같은 되묻기에 두 번 답하면 409 — 보완은 하나다
        expectError(answer(first, "다른 답"), 409, "CLARIFICATION_ALREADY_ANSWERED");
        assertThat(jdbc.queryForObject("select count(*) from supplement", Integer.class)).isEqualTo(1);
    }

    @Test
    void 다음_문항의_추천에_보완이_후보와_근거로_실린다() throws Exception {
        String done = awaitDone(submit(QUESTION_A));
        long first = ((Number) JsonPath.read(done, "$.clarifications[0].id")).longValue();
        String answered = answer(first, CHAT_ANSWER).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long supplementId = ((Number) JsonPath.read(answered, "$.supplement.id")).longValue();

        // 문항 B — AI 가 보완을 근거로 고른다(S-25)
        reply = new FakeReply(200, """
                {
                  "requirements": [{ "text": "맡은 역할", "status": "SUPPORTED", "experienceIds": [%1$d] }],
                  "recommendations": [{
                    "experienceId": %1$d, "reason": "직접 고친 범위가 보완에 있다", "emphasis": "구독 방식으로 바꾼 일",
                    "evidence": [{ "type": "CITATION", "id": %2$d }, { "type": "SUPPLEMENT", "id": %3$d }], "gaps": []
                  }],
                  "clarifications": []
                }""".formatted(chat, chatCitation, supplementId), 0);
        String second = awaitDone(submit("""
                {"organization": "OO 연합 동아리", "text": "팀 프로젝트에서 맡은 역할을 적어 주세요."}"""));

        String sent = RECEIVED.get(1);
        assertThat((List<?>) JsonPath.read(sent, "$.experiences[1].supplements")).hasSize(1);
        assertThat(((Number) JsonPath.read(sent, "$.experiences[1].supplements[0].id")).longValue()).isEqualTo(supplementId);
        assertThat((String) JsonPath.read(sent, "$.experiences[1].supplements[0].question")).startsWith("폴링을 WebSocket");
        assertThat((String) JsonPath.read(sent, "$.experiences[1].supplements[0].answer")).isEqualTo(CHAT_ANSWER);
        // 계약은 { id, question, answer } 만
        assertThat((Map<String, ?>) JsonPath.read(sent, "$.experiences[1].supplements[0]"))
                .containsOnlyKeys("id", "question", "answer");
        assertThat((List<?>) JsonPath.read(sent, "$.experiences[2].supplements")).isEmpty();

        assertThat((String) JsonPath.read(second, "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(second, "$.recommendations[0].evidence[1].type")).isEqualTo("SUPPLEMENT");
        assertThat(((Number) JsonPath.read(second, "$.recommendations[0].evidence[1].id")).longValue()).isEqualTo(supplementId);
    }

    @Test
    void 보완은_경험을_따라_옮겨지고_합쳐지고_경험을_지우면_사라진다() throws Exception {
        String done = awaitDone(submit(QUESTION_A));
        long first = ((Number) JsonPath.read(done, "$.clarifications[0].id")).longValue();
        answer(first, CHAT_ANSWER).andExpect(status().isCreated());

        // 옮기기 — 보완이 경험을 따라간다
        mvc.perform(post("/api/experiences/{id}/move", chat).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toActivityId\": " + studyId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplements[0].answer").value(CHAT_ANSWER));
        mvc.perform(get("/api/activities/{id}", studyId))
                .andExpect(jsonPath("$.experiences[1].id").value(chat))
                .andExpect(jsonPath("$.experiences[1].supplements[0].answer").value(CHAT_ANSWER));

        // 합치기 — 스터디를 바꿔요로 합쳐도 보완은 그 경험에 남는다
        mvc.perform(post("/api/activities/{id}/merge", appId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromActivityId\": " + studyId + "}"))
                .andExpect(status().isOk());
        String merged = mvc.perform(get("/api/activities/{id}", appId))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(merged, "$.experiences[?(@.id == " + chat + ")].supplements[0].answer"))
                .containsExactly(CHAT_ANSWER);

        // 경험을 지우면 보완도 지워지고, 그 되묻기에 다시 답하면 404 EXPERIENCE_NOT_FOUND
        mvc.perform(delete("/api/experiences/{id}", chat)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from supplement", Integer.class)).isZero();
        expectError(answer(first, CHAT_ANSWER), 404, "EXPERIENCE_NOT_FOUND");
    }

    @Test
    void 되묻기에_답하기_오류() throws Exception {
        String done = awaitDone(submit(QUESTION_A));
        long first = ((Number) JsonPath.read(done, "$.clarifications[0].id")).longValue();
        long second = ((Number) JsonPath.read(done, "$.clarifications[1].id")).longValue();

        expectError(answer(first, "  \n "), 400, "EMPTY_ANSWER");
        expectError(answer(first, ""), 400, "EMPTY_ANSWER");
        expectError(postAnswer(first, "{}"), 400, "INVALID_REQUEST");
        expectError(postAnswer(first, "{not json"), 400, "INVALID_REQUEST");
        expectError(answer(first, "가".repeat(2_001)), 400, "INVALID_REQUEST");
        expectError(answer(999_999, CHAT_ANSWER), 404, "CLARIFICATION_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from supplement", Integer.class)).isZero();

        // 다른 사용자의 되묻기는 없는 것과 같다
        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            expectError(TestLogin.mvc(context, other).perform(post("/api/clarifications/{id}/answer", first)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"answer\": \"남의 답\"}")),
                    404, "CLARIFICATION_NOT_FOUND");
        } finally {
            jdbc.update("delete from app_user where id = ?", other);
        }

        // 한도 딱 맞는 길이는 받는다
        answer(second, "가".repeat(2_000)).andExpect(status().isCreated());
    }

    // --- 보완 고치기 · 지우기 — BE#24 (S-24) ---

    @Test
    void 보완을_고치면_답만_바뀌고_질문_경험은_그대로다() throws Exception {
        long questionId = submit(QUESTION_A);
        long first = ((Number) JsonPath.read(awaitDone(questionId), "$.clarifications[0].id")).longValue();
        long supplementId = answeredSupplement(first);

        String fixed = "서버에 STOMP 엔드포인트를 만들고, 앱의 폴링 코드를 구독 방식으로 바꿨다. 재연결도 붙였다";
        patchSupplement(supplementId, "{\"answer\": \"" + fixed + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(supplementId))
                .andExpect(jsonPath("$.question").value("폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?"))
                .andExpect(jsonPath("$.answer").value(fixed))
                .andExpect(jsonPath("$.questionId").value(questionId))
                .andExpect(jsonPath("$.updatedAt").isString());

        // 새로고침해도 남고, 경험의 본문 · 근거 · 출처 상태는 그대로 · 되묻기는 여전히 답한 상태
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].id").value(chat))
                .andExpect(jsonPath("$.experiences[0].content").value("채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다"))
                .andExpect(jsonPath("$.experiences[0].sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.experiences[0].citations.length()").value(1))
                .andExpect(jsonPath("$.experiences[0].supplements.length()").value(1))
                .andExpect(jsonPath("$.experiences[0].supplements[0].answer").value(fixed));
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.clarifications[0].supplementId").value(supplementId));
    }

    @Test
    void 보완을_지우면_경험_되묻기_끝난_추천_다음_매칭에서_빠진다() throws Exception {
        long questionA = submit(QUESTION_A);
        long first = ((Number) JsonPath.read(awaitDone(questionA), "$.clarifications[0].id")).longValue();
        long supplementId = answeredSupplement(first);

        // 문항 B — AI 가 그 보완을 첫 근거로 골랐다(지우면 뒤 줄을 당겨 다시 매겨야 한다)
        reply = new FakeReply(200, """
                {
                  "requirements": [{ "text": "맡은 역할", "status": "SUPPORTED", "experienceIds": [%1$d] }],
                  "recommendations": [{
                    "experienceId": %1$d, "reason": "직접 고친 범위가 보완에 있다", "emphasis": "구독 방식으로 바꾼 일",
                    "evidence": [{ "type": "SUPPLEMENT", "id": %3$d }, { "type": "CITATION", "id": %2$d }], "gaps": []
                  }],
                  "clarifications": []
                }""".formatted(chat, chatCitation, supplementId), 0);
        long questionB = submit("""
                {"organization": "OO 연합 동아리", "text": "팀 프로젝트에서 맡은 역할을 적어 주세요."}""");
        assertThat((List<?>) JsonPath.read(awaitDone(questionB), "$.recommendations[0].evidence")).hasSize(2);

        mvc.perform(delete("/api/supplements/{id}", supplementId)).andExpect(status().isNoContent());

        // 그 보완을 가리키던 근거 줄이 지워지고 남은 줄은 0부터 다시 매겨진다(BE#37)
        assertThat(jdbc.queryForList("select position, type, ref_id from question_recommendation_evidence "
                + "where recommendation_id = (select id from question_recommendation where question_id = ?)",
                questionB)).containsExactly(Map.of("position", 0, "type", "CITATION", "ref_id", chatCitation));

        // 경험에서 빠진다
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[0].id").value(chat))
                .andExpect(jsonPath("$.experiences[0].supplements.length()").value(0))
                .andExpect(jsonPath("$.experiences[0].citations.length()").value(1));
        // 끝난 추천의 근거에서 빠진다 — 근거(citation)는 남는다
        mvc.perform(get("/api/questions/{id}", questionB))
                .andExpect(jsonPath("$.recommendations[0].evidence.length()").value(1))
                .andExpect(jsonPath("$.recommendations[0].evidence[0].type").value("CITATION"))
                .andExpect(jsonPath("$.recommendations[0].evidence[0].id").value(chatCitation));
        // 그 보완을 만든 되묻기는 안 답한 상태로 돌아간다
        mvc.perform(get("/api/questions/{id}", questionA))
                .andExpect(jsonPath("$.clarifications[0].supplementId").value(nullValue()));

        // 다음 매칭 후보에 실리지 않는다
        reply = new FakeReply(200, contractExample(), 0);
        awaitDone(submit(QUESTION_A));
        assertThat((List<?>) JsonPath.read(RECEIVED.get(2), "$.experiences[1].supplements")).isEmpty();

        // 다시 답할 수 있다
        answer(first, CHAT_ANSWER).andExpect(status().isCreated());
    }

    @Test
    void 보완_고치기_지우기_오류() throws Exception {
        long first = ((Number) JsonPath.read(awaitDone(submit(QUESTION_A)), "$.clarifications[0].id")).longValue();
        long supplementId = answeredSupplement(first);

        expectError(patchSupplement(supplementId, "{\"answer\": \"  \\n \"}"), 400, "EMPTY_ANSWER");
        expectError(patchSupplement(supplementId, "{\"answer\": \"\"}"), 400, "EMPTY_ANSWER");
        expectError(patchSupplement(supplementId, "{}"), 400, "INVALID_REQUEST");
        expectError(patchSupplement(supplementId, "{not json"), 400, "INVALID_REQUEST");
        expectError(patchSupplement(supplementId, "{\"answer\": \"" + "가".repeat(2_001) + "\"}"), 400, "INVALID_REQUEST");
        expectError(patchSupplement(999_999, "{\"answer\": \"답\"}"), 404, "SUPPLEMENT_NOT_FOUND");
        expectError(mvc.perform(delete("/api/supplements/{id}", 999_999)), 404, "SUPPLEMENT_NOT_FOUND");

        // 다른 사용자의 보완은 없는 것과 같다
        Long other = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            MockMvc otherMvc = TestLogin.mvc(context, other);
            expectError(otherMvc.perform(patch("/api/supplements/{id}", supplementId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"answer\": \"남의 답\"}")), 404, "SUPPLEMENT_NOT_FOUND");
            expectError(otherMvc.perform(delete("/api/supplements/{id}", supplementId)), 404, "SUPPLEMENT_NOT_FOUND");
        } finally {
            jdbc.update("delete from app_user where id = ?", other);
        }

        // 아무것도 바뀌지 않았고, 한도 딱 맞는 길이는 받는다
        assertThat(jdbc.queryForObject("select answer from supplement where id = ?", String.class, supplementId))
                .isEqualTo(CHAT_ANSWER);
        patchSupplement(supplementId, "{\"answer\": \"" + "가".repeat(2_000) + "\"}").andExpect(status().isOk());
    }

    // --- 오류 ---

    @Test
    void 문항_넣기_오류() throws Exception {
        expectError(postQuestion("""
                {"organization": null, "text": "  \\n "}"""), 400, "EMPTY_TEXT");
        expectError(postQuestion("""
                {"organization": "OO"}"""), 400, "INVALID_REQUEST");
        expectError(postQuestion("{\"organization\": \"" + "가".repeat(101) + "\", \"text\": \"문항\"}"),
                400, "INVALID_REQUEST");
        expectError(postQuestion("{not json"), 400, "INVALID_REQUEST");
        expectError(postQuestion("{\"organization\": null, \"text\": \"" + "가".repeat(5_001) + "\"}"),
                413, "TEXT_TOO_LONG");
        assertThat(questions.count()).isZero();
        assertThat(applications.count()).isZero();

        // 한도 딱 맞는 길이는 받는다
        long questionId = submit("{\"organization\": \"" + "가".repeat(100) + "\", \"text\": \"" + "가".repeat(5_000) + "\"}");
        awaitDone(questionId);
    }

    @Test
    void 조회_다시_시도_오류() throws Exception {
        long questionId = submit(QUESTION_A);
        awaitDone(questionId);
        expectError(mvc.perform(post("/api/questions/{id}/retry", questionId)), 409, "QUESTION_NOT_FAILED");
        expectError(mvc.perform(post("/api/questions/{id}/retry", 999_999)), 404, "QUESTION_NOT_FOUND");
        expectError(mvc.perform(get("/api/questions/{id}", 999_999)), 404, "QUESTION_NOT_FOUND");
    }

    // --- 지원(지원처 + 문항 여러 개) — BE#30 ---

    static final String APPLICATION = """
            {"organization": "OO 백엔드 부트캠프", "questions": [
              {"text": "기술적인 문제를 해결한 경험을 구체적으로 적어 주세요."},
              {"text": "지원 동기와 수료 후 목표를 적어 주세요."},
              {"text": "팀 프로젝트에서 맡은 역할을 적어 주세요."}
            ]}""";

    @Test
    void 지원을_넣으면_문항마다_추천이_돌고_넣은_순서로_보인다() throws Exception {
        String body = postApplication(APPLICATION)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.questionIds.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        long applicationId = ((Number) JsonPath.read(body, "$.applicationId")).longValue();
        List<Long> questionIds = ((List<Number>) JsonPath.read(body, "$.questionIds")).stream()
                .map(Number::longValue).toList();
        for (long id : questionIds) {
            String done = awaitDone(id);
            assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("COMPLETED");
            assertThat(((Number) JsonPath.read(done, "$.applicationId")).longValue()).isEqualTo(applicationId);
            assertThat((String) JsonPath.read(done, "$.organization")).isEqualTo("OO 백엔드 부트캠프");
        }

        // 문항마다 따로 /match — 지원처 이름은 지원의 것
        assertThat(RECEIVED).hasSize(3);
        assertThat(RECEIVED.stream().map(r -> (String) JsonPath.read(r, "$.question.organization")))
                .containsOnly("OO 백엔드 부트캠프");
        assertThat(RECEIVED.stream().map(r -> (String) JsonPath.read(r, "$.question.text")))
                .containsExactlyInAnyOrder("기술적인 문제를 해결한 경험을 구체적으로 적어 주세요.",
                        "지원 동기와 수료 후 목표를 적어 주세요.", "팀 프로젝트에서 맡은 역할을 적어 주세요.");

        mvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(applicationId))
                .andExpect(jsonPath("$.organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.questions.length()").value(3))
                .andExpect(jsonPath("$.questions[0].id").value(questionIds.get(0)))
                .andExpect(jsonPath("$.questions[0].applicationId").value(applicationId))
                .andExpect(jsonPath("$.questions[0].organization").value("OO 백엔드 부트캠프"))
                .andExpect(jsonPath("$.questions[0].text").value("기술적인 문제를 해결한 경험을 구체적으로 적어 주세요."))
                .andExpect(jsonPath("$.questions[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.questions[1].id").value(questionIds.get(1)))
                .andExpect(jsonPath("$.questions[2].id").value(questionIds.get(2)))
                .andExpect(jsonPath("$.questions[2].text").value("팀 프로젝트에서 맡은 역할을 적어 주세요."));

        // 문항 목록에도 지원 ID 가 붙는다
        mvc.perform(get("/api/questions"))
                .andExpect(jsonPath("$.questions.length()").value(3))
                .andExpect(jsonPath("$.questions[0].applicationId").value(applicationId))
                .andExpect(jsonPath("$.questions[0].organization").value("OO 백엔드 부트캠프"));
        // 문항마다 분석 1회
        assertThat(jdbc.queryForObject("select count from analysis_usage", Integer.class)).isEqualTo(3);
    }

    @Test
    void 지원에_문항을_더하면_그_지원의_끝에_붙고_지원처_이름을_따른다() throws Exception {
        long applicationId = submitApplication(APPLICATION);

        String body = addQuestion(applicationId, "{\"organization\": \"무시된다\", \"text\": \"본인의 장단점을 적어 주세요.\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andReturn().getResponse().getContentAsString();
        long added = ((Number) JsonPath.read(body, "$.questionId")).longValue();
        String done = awaitDone(added);
        assertThat((String) JsonPath.read(done, "$.organization")).isEqualTo("OO 백엔드 부트캠프");

        mvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(jsonPath("$.questions.length()").value(4))
                .andExpect(jsonPath("$.questions[3].id").value(added))
                .andExpect(jsonPath("$.questions[3].text").value("본인의 장단점을 적어 주세요."));
        awaitApplication(applicationId);
    }

    @Test
    void 지원_목록은_최근에_넣은_것부터이고_문항_하나_넣기도_지원이_된다() throws Exception {
        long first = submitApplication(APPLICATION);
        String single = postQuestion(QUESTION_A).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long second = ((Number) JsonPath.read(single, "$.applicationId")).longValue();
        long singleQuestion = ((Number) JsonPath.read(single, "$.questionId")).longValue();
        awaitApplication(first);
        awaitApplication(second);

        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications.length()").value(2))
                .andExpect(jsonPath("$.applications[0].id").value(second))
                .andExpect(jsonPath("$.applications[0].questions.length()").value(1))
                .andExpect(jsonPath("$.applications[0].questions[0].id").value(singleQuestion))
                .andExpect(jsonPath("$.applications[1].id").value(first))
                .andExpect(jsonPath("$.applications[1].questions.length()").value(3));
    }

    @Test
    void 남은_분석_횟수가_문항_수보다_적으면_아무것도_저장하지_않는다() throws Exception {
        jdbc.update("update app_user set daily_analysis_limit = 4 where id = ?", TestLogin.USER_ID);
        awaitDone(submit(QUESTION_A)); // 1회 씀 — 남은 3회

        expectError(postApplication("""
                {"organization": null, "questions": [{"text": "1"}, {"text": "2"}, {"text": "3"}, {"text": "4"}]}"""),
                429, "DAILY_LIMIT_EXCEEDED");
        assertThat(applications.count()).isEqualTo(1);
        assertThat(questions.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count from analysis_usage", Integer.class)).isEqualTo(1);

        // 딱 맞으면 받는다 — 그 뒤엔 문항 더하기도 429
        long applicationId = submitApplication("""
                {"organization": null, "questions": [{"text": "1"}, {"text": "2"}, {"text": "3"}]}""");
        expectError(addQuestion(applicationId, "{\"text\": \"4\"}"), 429, "DAILY_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("select count from analysis_usage", Integer.class)).isEqualTo(4);
        awaitApplication(applicationId);
    }

    @Test
    void 지원_넣기_오류() throws Exception {
        expectError(postApplication("{\"organization\": \"OO\"}"), 400, "INVALID_REQUEST");
        expectError(postApplication("{\"questions\": []}"), 400, "INVALID_REQUEST");
        expectError(postApplication("{\"questions\": [" + "{\"text\": \"문항\"},".repeat(10) + "{\"text\": \"문항\"}]}"),
                400, "INVALID_REQUEST");
        expectError(postApplication("{\"questions\": [{\"text\": \"문항\"}, {}]}"), 400, "INVALID_REQUEST");
        expectError(postApplication("{\"questions\": [null]}"), 400, "INVALID_REQUEST");
        expectError(postApplication("{\"questions\": [{\"text\": \"문항\"}, {\"text\": \" \"}]}"), 400, "EMPTY_TEXT");
        expectError(postApplication("{\"organization\": \"" + "가".repeat(101) + "\", \"questions\": [{\"text\": \"문항\"}]}"),
                400, "INVALID_REQUEST");
        expectError(postApplication("{\"questions\": [{\"text\": \"" + "가".repeat(5_001) + "\"}]}"), 413, "TEXT_TOO_LONG");
        expectError(postApplication("{not json"), 400, "INVALID_REQUEST");
        assertThat(applications.count()).isZero();
        assertThat(questions.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_usage", Integer.class)).isZero();

        expectError(mvc.perform(get("/api/applications/{id}", 999_999)), 404, "APPLICATION_NOT_FOUND");
        expectError(addQuestion(999_999, "{\"text\": \"문항\"}"), 404, "APPLICATION_NOT_FOUND");
    }

    @Test
    void 지원_하나의_문항은_20개까지() throws Exception {
        long applicationId = submitApplication("{\"questions\": [" + "{\"text\": \"문항\"},".repeat(9) + "{\"text\": \"문항\"}]}");
        expectError(addQuestion(applicationId, "{\"text\": \"  \"}"), 400, "EMPTY_TEXT");
        for (int i = 0; i < 10; i++) {
            addQuestion(applicationId, "{\"text\": \"더한 문항\"}").andExpect(status().isAccepted());
        }
        expectError(addQuestion(applicationId, "{\"text\": \"21번째\"}"), 400, "INVALID_REQUEST");
        assertThat(questions.count()).isEqualTo(20);
        awaitApplication(applicationId);
    }

    @Test
    void 다른_사용자의_지원은_보이지_않는다() throws Exception {
        long applicationId = submitApplication(APPLICATION);
        awaitApplication(applicationId);
        Long other = jdbc.queryForObject(
                "insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        try {
            MockMvc otherMvc = TestLogin.mvc(context, other);
            otherMvc.perform(get("/api/applications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applications.length()").value(0));
            expectError(otherMvc.perform(get("/api/applications/{id}", applicationId)), 404, "APPLICATION_NOT_FOUND");
            expectError(otherMvc.perform(post("/api/applications/{id}/questions", applicationId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"문항\"}")), 404, "APPLICATION_NOT_FOUND");
            expectError(otherMvc.perform(patch("/api/applications/{id}", applicationId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"jobRole\": null}")), 404, "APPLICATION_NOT_FOUND");
            long questionId = questions.findAll().getFirst().getId();
            expectError(otherMvc.perform(patch("/api/questions/{id}", questionId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"questionType\": \"OTHER\"}")), 404, "QUESTION_NOT_FOUND");
            assertThat(questions.count()).isEqualTo(3);
        } finally {
            jdbc.update("delete from app_user where id = ?", other);
        }
    }

    // --- 지원처 종류 · 직무 (S-42) · 문항 유형 (S-41) — BE#50 ---

    @Test
    void 지원처_종류_직무를_넣고_보낸_칸만_바꾸거나_비우고_매칭엔_싣지_않는다() throws Exception {
        long applicationId = submitApplication("""
                {"organization": "OO 백엔드 부트캠프", "organizationType": "BOOTCAMP", "jobRole": "BACKEND",
                 "questions": [{"text": "기술적인 문제를 해결한 경험을 적어 주세요."}]}""");
        awaitApplication(applicationId);

        mvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(jsonPath("$.organizationType").value("BOOTCAMP"))
                .andExpect(jsonPath("$.jobRole").value("BACKEND"))
                // 문항 요약에는 싣지 않는다 — 지원의 것
                .andExpect(jsonPath("$.questions[0].organizationType").doesNotExist());

        // 직무만 비운다 — 종류는 그대로, 추천은 다시 돌지 않는다
        patchApplication(applicationId, "{\"jobRole\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(applicationId))
                .andExpect(jsonPath("$.organizationType").value("BOOTCAMP"))
                .andExpect(jsonPath("$.jobRole").value(nullValue()))
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.questions[0].status").value("COMPLETED"));
        patchApplication(applicationId, "{\"organizationType\": \"CLUB\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationType").value("CLUB"))
                .andExpect(jsonPath("$.jobRole").value(nullValue()));
        patchApplication(applicationId, "{\"organizationType\": \"JOB\", \"jobRole\": \"INFRA\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/applications"))
                .andExpect(jsonPath("$.applications[0].organizationType").value("JOB"))
                .andExpect(jsonPath("$.applications[0].jobRole").value("INFRA"));

        // 문항 하나 넣기도 새 지원의 종류 · 직무를 받는다 — 안 보내면 null
        String single = postQuestion("""
                {"organization": "OO 연합 동아리", "organizationType": "CLUB", "jobRole": null, "text": "팀 프로젝트에서 맡은 역할을 적어 주세요."}""")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long singleApplication = ((Number) JsonPath.read(single, "$.applicationId")).longValue();
        long plain = submitApplication("{\"questions\": [{\"text\": \"지원 동기를 적어 주세요.\"}]}");
        awaitApplication(singleApplication);
        awaitApplication(plain);
        mvc.perform(get("/api/applications/{id}", singleApplication))
                .andExpect(jsonPath("$.organizationType").value("CLUB"))
                .andExpect(jsonPath("$.jobRole").value(nullValue()));
        mvc.perform(get("/api/applications/{id}", plain))
                .andExpect(jsonPath("$.organizationType").value(nullValue()))
                .andExpect(jsonPath("$.jobRole").value(nullValue()));

        // 매칭 요청에는 종류 · 직무가 없다(S-42) — PATCH 로도 다시 돌지 않았다
        assertThat(RECEIVED).hasSize(3);
        assertThat(RECEIVED).allSatisfy(sent -> assertThat(sent)
                .doesNotContain("organizationType").doesNotContain("jobRole")
                .doesNotContain("BOOTCAMP").doesNotContain("CLUB").doesNotContain("BACKEND"));
    }

    @Test
    void 지원처_종류_직무_오류() throws Exception {
        expectError(postApplication("""
                {"organizationType": "COMPANY", "questions": [{"text": "문항"}]}"""), 400, "INVALID_REQUEST");
        expectError(postApplication("""
                {"jobRole": "서버", "questions": [{"text": "문항"}]}"""), 400, "INVALID_REQUEST");
        expectError(postQuestion("""
                {"jobRole": "backend", "text": "문항"}"""), 400, "INVALID_REQUEST");
        assertThat(applications.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_usage", Integer.class)).isZero();

        long applicationId = submitApplication("""
                {"organizationType": "BOOTCAMP", "jobRole": "BACKEND", "questions": [{"text": "문항"}]}""");
        expectError(patchApplication(applicationId, "{}"), 400, "INVALID_REQUEST");
        expectError(patchApplication(applicationId, "{\"organization\": \"새 이름\"}"), 400, "INVALID_REQUEST");
        // 하나라도 틀리면 아무것도 바꾸지 않는다
        expectError(patchApplication(applicationId, "{\"organizationType\": \"CLUB\", \"jobRole\": \"X\"}"),
                400, "INVALID_REQUEST");
        expectError(patchApplication(applicationId, "{\"jobRole\": 1}"), 400, "INVALID_REQUEST");
        expectError(mvc.perform(patch("/api/applications/{id}", applicationId)), 400, "INVALID_REQUEST");
        expectError(patchApplication(999_999, "{\"jobRole\": null}"), 404, "APPLICATION_NOT_FOUND");
        mvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(jsonPath("$.organizationType").value("BOOTCAMP"))
                .andExpect(jsonPath("$.jobRole").value("BACKEND"));
        awaitApplication(applicationId);
    }

    @Test
    void 매칭이_문항_유형을_AI_로_붙이고_없으면_null() throws Exception {
        reply = new FakeReply(200, withType(contractExample(), "PROBLEM_SOLVING"), 0);
        long typed = submit(QUESTION_A);
        String done = awaitDone(typed);
        assertThat((String) JsonPath.read(done, "$.questionType")).isEqualTo("PROBLEM_SOLVING");
        assertThat((String) JsonPath.read(done, "$.questionTypeBy")).isEqualTo("AI");

        // AI 가 아직 유형을 안 붙이면(AI 이슈 머지 전) null 로 두고 실패로 보지 않는다
        reply = new FakeReply(200, contractExample(), 0);
        long untyped = submit(QUESTION_A);
        String plain = awaitDone(untyped);
        assertThat((String) JsonPath.read(plain, "$.status")).isEqualTo("COMPLETED");
        assertThat((Object) JsonPath.read(plain, "$.questionType")).isNull();
        assertThat((Object) JsonPath.read(plain, "$.questionTypeBy")).isNull();

        // 문항 요약(목록 · 지원)에도 실린다
        mvc.perform(get("/api/questions"))
                .andExpect(jsonPath("$.questions[0].id").value(untyped))
                .andExpect(jsonPath("$.questions[0].questionType").value(nullValue()))
                .andExpect(jsonPath("$.questions[0].questionTypeBy").value(nullValue()))
                .andExpect(jsonPath("$.questions[1].questionType").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.questions[1].questionTypeBy").value("AI"));
        long applicationId = ((Number) JsonPath.read(done, "$.applicationId")).longValue();
        mvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(jsonPath("$.questions[0].questionType").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.questions[0].questionTypeBy").value("AI"));
    }

    @Test
    void 사용자가_고친_유형은_매칭_다시_시도가_덮지_않는다() throws Exception {
        // 끝난 문항 — AI 값을 고친다
        reply = new FakeReply(200, withType(contractExample(), "PROBLEM_SOLVING"), 0);
        long completed = submit(QUESTION_A);
        awaitDone(completed);
        patchQuestion(completed, "{\"questionType\": \"ACHIEVEMENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(completed))
                .andExpect(jsonPath("$.questionType").value("ACHIEVEMENT"))
                .andExpect(jsonPath("$.questionTypeBy").value("USER"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendations.length()").value(2));

        // 분석 중인 문항 — 고른 뒤 매칭이 끝나도 그대로
        reply = new FakeReply(200, withType(contractExample(), "PROBLEM_SOLVING"), 500);
        long analyzing = submit(QUESTION_A);
        patchQuestion(analyzing, "{\"questionType\": \"GROWTH\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.questionTypeBy").value("USER"));
        String afterMatch = awaitDone(analyzing);
        assertThat((String) JsonPath.read(afterMatch, "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(afterMatch, "$.questionType")).isEqualTo("GROWTH");
        assertThat((String) JsonPath.read(afterMatch, "$.questionTypeBy")).isEqualTo("USER");

        // 실패한 문항 — 고른 뒤 다시 시도해도 그대로
        reply = new FakeReply(502, "{\"error\": {\"code\": \"LLM_ERROR\", \"message\": \"down\"}}", 0);
        long failed = submit(QUESTION_A);
        String failedBody = awaitDone(failed);
        assertThat((String) JsonPath.read(failedBody, "$.status")).isEqualTo("FAILED");
        assertThat((Object) JsonPath.read(failedBody, "$.questionType")).isNull();
        patchQuestion(failed, "{\"questionType\": \"CONFLICT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.questionType").value("CONFLICT"));
        reply = new FakeReply(200, withType(contractExample(), "PROBLEM_SOLVING"), 0);
        mvc.perform(post("/api/questions/{id}/retry", failed))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.questionType").value("CONFLICT"))
                .andExpect(jsonPath("$.questionTypeBy").value("USER"));
        String retried = awaitDone(failed);
        assertThat((String) JsonPath.read(retried, "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(retried, "$.questionType")).isEqualTo("CONFLICT");
        assertThat((String) JsonPath.read(retried, "$.questionTypeBy")).isEqualTo("USER");
    }

    @Test
    void 문항_유형_고치기_오류() throws Exception {
        reply = new FakeReply(200, withType(contractExample(), "PROBLEM_SOLVING"), 0);
        long questionId = submit(QUESTION_A);
        awaitDone(questionId);
        expectError(patchQuestion(questionId, "{}"), 400, "INVALID_REQUEST");
        expectError(patchQuestion(questionId, "{\"questionType\": null}"), 400, "INVALID_REQUEST");
        expectError(patchQuestion(questionId, "{\"questionType\": \"HOBBY\"}"), 400, "INVALID_REQUEST");
        expectError(patchQuestion(questionId, "{\"questionType\": 3}"), 400, "INVALID_REQUEST");
        expectError(mvc.perform(patch("/api/questions/{id}", questionId)), 400, "INVALID_REQUEST");
        expectError(patchQuestion(999_999, "{\"questionType\": \"OTHER\"}"), 404, "QUESTION_NOT_FOUND");
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.questionType").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.questionTypeBy").value("AI"));
    }

    // --- 도움 ---

    // AI 응답에 문항 유형을 붙인다(ai-api.md 「POST /match」 questionType)
    private static String withType(String matchResponse, String questionType) {
        return matchResponse.replaceFirst("\\{", "{\"questionType\": \"" + questionType + "\",");
    }

    private ResultActions patchApplication(long applicationId, String json) throws Exception {
        return mvc.perform(patch("/api/applications/{id}", applicationId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patchQuestion(long questionId, String json) throws Exception {
        return mvc.perform(patch("/api/questions/{id}", questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long submit(String json) throws Exception {
        String body = postQuestion(json).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.questionId")).longValue();
    }

    private ResultActions answer(long clarificationId, String text) throws Exception {
        return postAnswer(clarificationId, "{\"answer\": \"" + text.replace("\n", "\\n") + "\"}");
    }

    private ResultActions postAnswer(long clarificationId, String json) throws Exception {
        return mvc.perform(post("/api/clarifications/{id}/answer", clarificationId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long answeredSupplement(long clarificationId) throws Exception {
        String body = answer(clarificationId, CHAT_ANSWER).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.supplement.id")).longValue();
    }

    private ResultActions patchSupplement(long supplementId, String json) throws Exception {
        return mvc.perform(patch("/api/supplements/{id}", supplementId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postQuestion(String json) throws Exception {
        return mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postApplication(String json) throws Exception {
        return mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long submitApplication(String json) throws Exception {
        String body = postApplication(json).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.applicationId")).longValue();
    }

    private ResultActions addQuestion(long applicationId, String json) throws Exception {
        return mvc.perform(post("/api/applications/{id}/questions", applicationId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // 지원의 문항 추천이 다 끝날 때까지 — 표를 비우기 전에 뒤에서 도는 작업을 끝낸다
    private void awaitApplication(long applicationId) throws Exception {
        String body = mvc.perform(get("/api/applications/{id}", applicationId)).andReturn().getResponse().getContentAsString();
        for (Number id : (List<Number>) JsonPath.read(body, "$.questions[*].id")) {
            awaitDone(id.longValue());
        }
    }

    // 추천은 다른 스레드에서 돈다 — 끝날 때까지 문항을 조회한다(FE 폴링과 같다)
    private String awaitDone(long questionId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/questions/{id}", questionId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (!"ANALYZING".equals(JsonPath.read(body, "$.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("문항 " + questionId + " 의 추천이 15초 안에 끝나지 않았습니다");
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}

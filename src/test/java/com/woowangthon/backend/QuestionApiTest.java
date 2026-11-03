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
        jdbc.execute("truncate table question_recommendation_evidence, question_recommendation, question_requirement, "
                + "supplement, clarification, question, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity, source");
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
                .andReturn().getResponse().getContentAsString();
        long questionId = ((Number) JsonPath.read(submitted, "$.questionId")).longValue();

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
        Question question = questions.save(new Question(TestLogin.USER_ID, null, "끊긴 문항"));

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

    // --- 도움 ---

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

    private ResultActions postQuestion(String json) throws Exception {
        return mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content(json));
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

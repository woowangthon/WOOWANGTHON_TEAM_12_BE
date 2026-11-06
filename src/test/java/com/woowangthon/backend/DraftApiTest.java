package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.woowangthon.backend.job.JobStatus;
import com.woowangthon.backend.mcp.McpChangeService;
import com.woowangthon.backend.mcp.McpWriteService;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.Draft;
import com.woowangthon.backend.question.DraftFrame;
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
    static final String ASK = "WebSocket 으로 바꾼 뒤 메시지가 제때 오는지 어떻게 확인했나요?";

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

    @Autowired
    McpWriteService mcpWrites;

    @Autowired
    McpChangeService mcpChanges;

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

    // 같은 답(contractExample)이 평소엔 통과한다 — 다른 이유로 실패한 게 아니다
    @Test
    void 외부_AI_가_고친_경험은_사용자가_정하기_전엔_EXPERIENCE_근거가_되지_않는다() throws Exception {
        mcpWrites.updateExperience(TestLogin.USER_ID, "claude-ai", study, "스터디원 6명의 풀이를 매주 리뷰했다",
                "스터디원 8명의 풀이를 매주 리뷰했다", "사용자가 대화에서 고침");

        start(chat, study).andExpect(status().isAccepted());
        String failed = awaitDraft();

        assertThat((String) JsonPath.read(failed, "$.draft.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.draft.error.code")).isEqualTo("AI_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from draft_sentence_evidence", Integer.class)).isZero();
    }

    @Test
    void 외부_AI_고치기를_되돌리면_경험이_다시_EXPERIENCE_근거가_된다() throws Exception {
        long change = mcpWrites.updateExperience(TestLogin.USER_ID, "claude-ai", study, "스터디원 6명의 풀이를 매주 리뷰했다",
                "스터디원 8명의 풀이를 매주 리뷰했다", "사용자가 대화에서 고침").changeId();
        mcpChanges.undo(TestLogin.USER_ID, change);

        start(chat, study).andExpect(status().isAccepted());

        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");
    }

    @Test
    void 맞아요를_누른_경험은_EXPERIENCE_근거가_된다() throws Exception {
        long change = mcpWrites.updateExperience(TestLogin.USER_ID, "claude-ai", study, "스터디원 6명의 풀이를 매주 리뷰했다",
                "스터디원 8명의 풀이를 매주 리뷰했다", "사용자가 대화에서 고침").changeId();
        mcpChanges.confirm(TestLogin.USER_ID, change);

        start(chat, study).andExpect(status().isAccepted());

        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update app_user set daily_analysis_limit = null where id = ?", TestLogin.USER_ID);
        jdbc.execute("truncate table mcp_change");
        jdbc.execute("truncate table guideline");
        jdbc.execute("truncate table new_fact_candidate, answer_dismissed, answer, draft_sentence_evidence, draft_sentence, draft_omitted, draft_guideline, draft_experience, draft, "
                + "grounding_sentence_evidence, grounding_sentence, grounding_check, "
                + "question_recommendation_evidence, question_recommendation, question_requirement, supplement, clarification, "
                + "question, application, analysis_usage, analysis_job_activity, analysis_job, citation, experience, activity_link, activity, source_section, source");
    }

    private String contractExample() {
        return """
                {
                  "sentences": [
                    { "kind": "FACT", "text": "%s", "evidence": [{ "type": "CITATION", "id": %d }] },
                    { "kind": "FACT", "text": "%s", "evidence": [{ "type": "SUPPLEMENT", "id": %d }] },
                    { "kind": "FACT", "text": "%s", "evidence": [{ "type": "EXPERIENCE", "id": %d }] }
                  ],
                  "omitted": [
                    { "text": "바꾼 뒤 메시지가 제때 오는지 확인한 방법", "question": "%s", "experienceId": %d },
                    { "text": "Java · Spring 을 쓴 경험", "question": null, "experienceId": null }
                  ],
                  "model": "test-model"
                }
                """.formatted(S1, chatCitation, S2, chatSupplement, S3, study, ASK, chat);
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

        // AI 에는 문항과 고른 경험만, 고른 순서대로 — 근거는 원본에서 찾은 것만, 보완은 전부
        assertThat(RECEIVED).hasSize(1);
        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.question.organization")).isEqualTo("OO 백엔드 부트캠프");
        assertThat((String) JsonPath.read(sent, "$.question.text")).isEqualTo("기술적인 문제를 해결한 경험을 적어 주세요.");
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[*].id")).containsExactly((int) study, (int) chat);
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[1].citations[*].id")).containsExactly((int) chatCitation);
        assertThat((List<Integer>) JsonPath.read(sent, "$.experiences[1].supplements[*].id")).containsExactly((int) chatSupplement);
        assertThat((String) JsonPath.read(sent, "$.experiences[0].sourceStatus")).isEqualTo("USER_ENTERED");
        // 저장된 매칭 결과를 그대로 싣는다 — 고른 경험의 것만, 요구사항은 experienceIds 없이(ETC#141). 직무가 없는 지원이라 칸이 없다
        assertThat((List<String>) JsonPath.read(sent, "$.question.requirements[*].text")).containsExactly("기술 문제 해결");
        assertThat((List<String>) JsonPath.read(sent, "$.question.requirements[*].status")).containsExactly("SUPPORTED");
        assertThat(sent).doesNotContain("experienceIds").doesNotContain("jobRole");
        assertThat((String) JsonPath.read(sent, "$.experiences[1].reason")).isEqualTo("추천 이유 비밀");
        assertThat((String) JsonPath.read(sent, "$.experiences[1].emphasis")).isEqualTo("강조점 비밀");
        assertThat((List<String>) JsonPath.read(sent, "$.experiences[1].gaps")).containsExactly("부족한 점 비밀");
        assertThat((String) JsonPath.read(sent, "$.experiences[0].reason")).isEqualTo("이유");
        assertThat((List<String>) JsonPath.read(sent, "$.experiences[0].gaps")).isEmpty();
        assertThat((List<?>) JsonPath.read(sent, "$.experiences[0].citations")).isEmpty();

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.experienceIds.length()").value(2))
                .andExpect(jsonPath("$.draft.sentences.length()").value(3))
                .andExpect(jsonPath("$.draft.sentences[0].text").value(S1))
                .andExpect(jsonPath("$.draft.sentences[*].kind", contains("FACT", "FACT", "FACT")))
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
                .andExpect(jsonPath("$.draft.omitted[0].text").value("바꾼 뒤 메시지가 제때 오는지 확인한 방법"))
                .andExpect(jsonPath("$.draft.omitted[0].question").value(ASK))
                .andExpect(jsonPath("$.draft.omitted[0].experienceId").value(chat))
                .andExpect(jsonPath("$.draft.omitted[0].clarificationId").isNumber())
                .andExpect(jsonPath("$.draft.omitted[0].supplementId").value(nullValue()))
                // 경험에 붙지 않는 빈칸 — text 만, 나머지 네 칸은 null 로 싣는다
                .andExpect(jsonPath("$.draft.omitted[1].text").value("Java · Spring 을 쓴 경험"))
                .andExpect(jsonPath("$.draft.omitted[1].question").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[1].experienceId").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[1].clarificationId").value(nullValue()))
                .andExpect(jsonPath("$.draft.omitted[1].supplementId").value(nullValue()))
                // 초안의 되묻기는 추천의 되묻기에 섞이지 않는다
                .andExpect(jsonPath("$.clarifications.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isEqualTo(1);

        // 분석 1회로 센다 · 문항의 추천은 그대로
        assertThat(used()).isEqualTo(before + 1);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendations.length()").value(3));
    }

    @Test
    void 지원에_직무가_있으면_초안_요청에_싣는다() throws Exception {
        jdbc.update("update application set job_role = 'BACKEND'");

        start(study).andExpect(status().isAccepted());
        awaitDraft();

        String sent = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(sent, "$.question.jobRole")).isEqualTo("BACKEND");
        // 고르지 않은 경험의 추천 해석은 싣지 않는다
        assertThat(sent).doesNotContain("비밀");
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
        draftReply = new FakeReply(200,
                "{\"sentences\": [], \"omitted\": [{\"text\": \"경험 전부\", \"question\": null, \"experienceId\": null}]}", 0);
        start(bare).andExpect(status().isAccepted());

        String done = awaitDraft();
        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((List<?>) JsonPath.read(done, "$.draft.sentences")).isEmpty();
        assertThat((List<String>) JsonPath.read(done, "$.draft.omitted[*].text")).containsExactly("경험 전부");
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
                .andExpect(jsonPath("$.draft.sentences[1].kind").value("FACT")) // 근거가 지워져도 OPINION 으로 바꾸지 않는다
                .andExpect(jsonPath("$.draft.sentences[2].evidence.length()").value(0));

        // 그새 지워진 경험은 고를 수 없다
        expectError(start(study), 400, "INVALID_REQUEST");

        draftReply = new FakeReply(200, """
                {"sentences": [{"kind": "FACT", "text": "새 문장", "evidence": [{"type": "CITATION", "id": %d}]}], "omitted": []}"""
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

    // --- 빠진 사실에 답하고 다시 만들기(ETC#139) ---

    @Test
    void 빠진_사실에_답하면_보완이_되고_다시_만들면_새_보완을_싣는다() throws Exception {
        start(chat, study).andExpect(status().isAccepted());
        String done = awaitDraft();
        long asked = ((Number) JsonPath.read(done, "$.draft.omitted[0].clarificationId")).longValue();
        int before = used();

        // 답은 그 경험의 보완 — 질문은 저장한 빠진 사실의 질문, questionId 는 지원 문항. AI 를 부르지 않고 분석을 세지 않는다
        String saved = mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\": \"두 기기로 메시지를 보내고 양쪽 화면에 도착하는지 확인했다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.experienceId").value(chat))
                .andExpect(jsonPath("$.supplement.question").value(ASK))
                .andExpect(jsonPath("$.supplement.questionId").value(questionId))
                .andReturn().getResponse().getContentAsString();
        long answered = ((Number) JsonPath.read(saved, "$.supplement.id")).longValue();
        assertThat(used()).isEqualTo(before);
        assertThat(RECEIVED).hasSize(1);

        // 새로고침해도 답한 상태 · 초안 문장은 그대로 · 활동 세부의 그 경험에 「내가 입력」 보완으로
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted[0].clarificationId").value(asked))
                .andExpect(jsonPath("$.draft.omitted[0].supplementId").value(answered))
                .andExpect(jsonPath("$.draft.sentences.length()").value(3));
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences[?(@.id == %d)].supplements[*].id".formatted(chat)).value(
                        org.hamcrest.Matchers.hasItem((int) answered)));
        expectError(mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\": \"또\"}")), 409, "CLARIFICATION_ALREADY_ANSWERED");

        // 보완을 지우면 다시 답할 수 있게 null 로 돌아간다
        jdbc.update("delete from supplement where id = ?", answered);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted[0].supplementId").value(nullValue()));
        answered = ((Number) JsonPath.read(mvc.perform(post("/api/clarifications/{id}/answer", asked)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\": \"두 기기로 확인했다\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.supplement.id"))
                .longValue();

        // 다시 만들기 — 저장한 보완을 싣고, 예전 초안의 되묻기는 무효, 보완은 남는다
        start(chat, study).andExpect(status().isAccepted());
        String again = awaitDraft();
        assertThat(RECEIVED).hasSize(2);
        assertThat((List<Integer>) JsonPath.read(RECEIVED.get(1), "$.experiences[0].supplements[*].id"))
                .containsExactly((int) chatSupplement, (int) answered);
        long newAsked = ((Number) JsonPath.read(again, "$.draft.omitted[0].clarificationId")).longValue();
        assertThat(newAsked).isNotEqualTo(asked);
        assertThat((Object) JsonPath.read(again, "$.draft.omitted[0].supplementId")).isNull();
        expectError(mvc.perform(post("/api/clarifications/{id}/answer", asked).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\": \"늦은 답\"}")), 404, "CLARIFICATION_NOT_FOUND");
        assertThat(supplements.findById(answered)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isEqualTo(1);

        // 경험이 지워지면 그 빈칸은 응답에서 빠진다 — 붙지 않은 빈칸은 남는다
        jdbc.update("delete from experience where id = ?", chat);
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.omitted.length()").value(1))
                .andExpect(jsonPath("$.draft.omitted[0].experienceId").value(nullValue()));
    }

    @Test
    void 옛_문자열_형식_초안은_없는_것으로_보이고_다시_만들면_풀린다() throws Exception {
        start(chat, study).andExpect(status().isAccepted());
        awaitDraft();
        jdbc.update("update draft set legacy_omitted = true");

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft").value(nullValue()))
                .andExpect(jsonPath("$.recommendations.length()").value(3));

        start(chat, study).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");
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
    void 생각_문장은_근거_없이_저장하고_종류를_그대로_돌려준다() throws Exception {
        draftReply = new FakeReply(200, """
                {"sentences": [
                  {"kind": "FACT", "text": "사실", "evidence": [{"type": "CITATION", "id": %d}]},
                  {"kind": "OPINION", "text": "앞으로도 원인을 살피는 개발자가 되고 싶습니다.", "evidence": []},
                  {"kind": "OPINION", "text": "이 경험을 지원 직무에 잇고 싶습니다."}
                ], "omitted": []}""".formatted(chatCitation), 0);
        start(chat).andExpect(status().isAccepted());
        String done = awaitDraft();
        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        assertThat((List<String>) JsonPath.read(done, "$.draft.sentences[*].kind")).containsExactly("FACT", "OPINION", "OPINION");
        assertThat((List<?>) JsonPath.read(done, "$.draft.sentences[1].evidence")).isEmpty();
        assertThat((List<?>) JsonPath.read(done, "$.draft.sentences[2].evidence")).isEmpty();
        assertThat(jdbc.queryForList("select kind from draft_sentence order by position", String.class))
                .containsExactly("FACT", "OPINION", "OPINION");
    }

    @Test
    void 계약을_어긴_응답이면_아무것도_저장하지_않고_실패() throws Exception {
        String okSentence = "{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}".formatted(chatCitation);
        String gap = "{\"text\": \"빈칸\", \"question\": null, \"experienceId\": null}";
        String opinion = "{\"kind\": \"OPINION\", \"text\": \"포부\", \"evidence\": []}";
        List<String> bodies = List.of(
                // kind 빠짐 · 값 목록 밖 · 근거 붙은 OPINION · OPINION 3개 넘음
                "{\"sentences\": [{\"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                "{\"sentences\": [{\"kind\": \"GUESS\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                "{\"sentences\": [{\"kind\": \"OPINION\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                "{\"sentences\": [" + okSentence + "," + String.join(",", java.util.Collections.nCopies(4, opinion)) + "]}",
                // 받칠 것이 있는데 문장 0개
                "{\"sentences\": [], \"omitted\": []}",
                "{\"omitted\": []}",
                // 15개 넘음
                "{\"sentences\": [" + String.join(",", java.util.Collections.nCopies(16, okSentence)) + "]}",
                // 빈 문장 · 줄바꿈
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \" \", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"한 줄\\n두 줄\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation),
                // 근거 0개 · 지어낸 ID · 원본에서 못 찾아 보내지 않은 근거 · 값 목록 밖 · 같은 근거 두 번
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": []}]}",
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": 999999}]}]}",
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(unmatchedCitation),
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"GUESS\", \"id\": 1}]}]}",
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"CITATION\", \"id\": %d}, {\"type\": \"CITATION\", \"id\": %d}]}]}".formatted(chatCitation, chatCitation),
                // USER_ENTERED 가 아닌 경험 본문을 근거로
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}]}]}".formatted(chat),
                // 보내지 않은 경험의 본문을 근거로
                "{\"sentences\": [{\"kind\": \"FACT\", \"text\": \"문장\", \"evidence\": [{\"type\": \"EXPERIENCE\", \"id\": %d}]}]}".formatted(stranger),
                // omitted 5개 넘음 · 빈 text · 예전 문자열 배열 · 질문과 경험의 null 짝 · 빈 질문 · 보내지 않은 경험
                "{\"sentences\": [" + okSentence + "], \"omitted\": [" + String.join(",", java.util.Collections.nCopies(6, gap)) + "]}",
                "{\"sentences\": [" + okSentence + "], \"omitted\": [{\"text\": \" \", \"question\": null, \"experienceId\": null}]}",
                "{\"sentences\": [" + okSentence + "], \"omitted\": [\"예전 문자열\"]}",
                "{\"sentences\": [" + okSentence + "], \"omitted\": [{\"text\": \"빈칸\", \"question\": \"질문?\", \"experienceId\": null}]}",
                "{\"sentences\": [" + okSentence + "], \"omitted\": [{\"text\": \"빈칸\", \"question\": null, \"experienceId\": %d}]}".formatted(chat),
                "{\"sentences\": [" + okSentence + "], \"omitted\": [{\"text\": \"빈칸\", \"question\": \" \", \"experienceId\": %d}]}".formatted(chat),
                "{\"sentences\": [" + okSentence + "], \"omitted\": [{\"text\": \"빈칸\", \"question\": \"질문?\", \"experienceId\": %d}]}".formatted(stranger));
        for (String body : bodies) {
            jdbc.update("delete from analysis_usage where user_id = ?", TestLogin.USER_ID); // 경우가 많아 하루 한도에 닿지 않게
            draftReply = new FakeReply(200, body, 0);
            start(chat, study).andExpect(status().isAccepted());
            String failed = awaitDraft();
            assertThat((String) JsonPath.read(failed, "$.draft.status")).as(body).isEqualTo("FAILED");
            assertThat((String) JsonPath.read(failed, "$.draft.error.code")).as(body).isEqualTo("AI_FAILED");
            assertThat((String) JsonPath.read(failed, "$.draft.error.message")).as(body).startsWith("INVALID_RESPONSE");
        }
        assertThat(jdbc.queryForObject("select count(*) from draft_sentence", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from draft_omitted", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from clarification", Integer.class)).isZero();
    }

    @Test
    void 서버가_다시_켜지면_만들던_초안은_실패가_된다() {
        Draft draft = new Draft(questionId);
        draft.start(List.of(chat), DraftFrame.NONE, List.of());
        drafts.save(draft);

        cleaner.run(null);

        Draft after = drafts.findByQuestionId(questionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    // --- 구성 틀 · 작성 지침 (S-49 · S-50) ---

    @Test
    void 틀과_켜_둔_지침을_싣고_반영_여부를_초안에_남긴다() throws Exception {
        List<Long> ids = saveGuidelines("첫 문장에 결과를 쓴다", "결과는 꼭 수치로 쓴다", "꺼 둔 지침");
        mvc.perform(patch("/api/guidelines/{id}", ids.get(2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": false}")).andExpect(status().isOk());
        draftReply = new FakeReply(200, withGuidelines("""
                [{ "id": %d, "applied": true, "note": null },
                 { "id": %d, "applied": false, "note": "자료에 수치가 없어 넣지 않았어요" }]
                """.formatted(ids.get(0), ids.get(1))), 0);

        postJson("{\"experienceIds\": [" + chat + ", " + study + "], \"frame\": \"STAR\"}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.frame").value("STAR"))
                .andExpect(jsonPath("$.guidelines.length()").value(0));

        String done = awaitDraft();
        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("COMPLETED");
        String received = RECEIVED.getFirst();
        assertThat((String) JsonPath.read(received, "$.question.frame")).isEqualTo("STAR");
        assertThat((List<String>) JsonPath.read(received, "$.guidelines[*].text"))
                .containsExactly("첫 문장에 결과를 쓴다", "결과는 꼭 수치로 쓴다");
        assertThat(((Number) JsonPath.read(received, "$.guidelines[0].id")).longValue()).isEqualTo(ids.get(0));
        assertThat(received).doesNotContain("멘토 피드백").doesNotContain("꺼 둔 지침");

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.frame").value("STAR"))
                .andExpect(jsonPath("$.draft.guidelines.length()").value(2))
                .andExpect(jsonPath("$.draft.guidelines[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$.draft.guidelines[0].text").value("첫 문장에 결과를 쓴다"))
                .andExpect(jsonPath("$.draft.guidelines[0].source").value("멘토 피드백"))
                .andExpect(jsonPath("$.draft.guidelines[0].applied").value(true))
                .andExpect(jsonPath("$.draft.guidelines[0].note").value(nullValue()))
                .andExpect(jsonPath("$.draft.guidelines[1].applied").value(false))
                .andExpect(jsonPath("$.draft.guidelines[1].note").value("자료에 수치가 없어 넣지 않았어요"));
    }

    @Test
    void 지침을_고치거나_지워도_초안의_지침은_그대로다() throws Exception {
        List<Long> ids = saveGuidelines("고칠 지침", "지울 지침");
        draftReply = new FakeReply(200, withGuidelines("""
                [{ "id": %d, "applied": true, "note": null }, { "id": %d, "applied": true, "note": null }]
                """.formatted(ids.get(0), ids.get(1))), 0);
        start(chat, study).andExpect(status().isAccepted());
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");

        mvc.perform(patch("/api/guidelines/{id}", ids.get(0)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"고친 글\", \"source\": \"고친 출처\", \"enabled\": false}")).andExpect(status().isOk());
        mvc.perform(delete("/api/guidelines/{id}", ids.get(1))).andExpect(status().isNoContent());

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.guidelines[*].text", contains("고칠 지침", "지울 지침")))
                .andExpect(jsonPath("$.draft.guidelines[*].source", contains("멘토 피드백", "멘토 피드백")))
                .andExpect(jsonPath("$.draft.guidelines[1].id").value(ids.get(1)));
    }

    @Test
    void 시작한_뒤에_지침을_바꿔도_시작할_때_켠_지침이_실린다() throws Exception {
        List<Long> ids = saveGuidelines("시작할 때 글");
        draftReply = new FakeReply(200, withGuidelines("[{ \"id\": %d, \"applied\": true, \"note\": null }]".formatted(ids.get(0))), 300);

        start(chat, study).andExpect(status().isAccepted());
        mvc.perform(delete("/api/guidelines/{id}", ids.get(0))).andExpect(status().isNoContent());
        awaitDraft();

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.status").value("COMPLETED"))
                .andExpect(jsonPath("$.draft.guidelines[0].text").value("시작할 때 글"));
    }

    @Test
    void 틀을_빼고_지침이_없으면_칸을_싣지_않고_NONE_이다() throws Exception {
        start(chat, study).andExpect(status().isAccepted()).andExpect(jsonPath("$.frame").value("NONE"));
        assertThat((String) JsonPath.read(awaitDraft(), "$.draft.status")).isEqualTo("COMPLETED");

        assertThat(RECEIVED.getFirst()).doesNotContain("frame").doesNotContain("guidelines");
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.frame").value("NONE"))
                .andExpect(jsonPath("$.draft.guidelines.length()").value(0));
    }

    @Test
    void 틀_없이를_고르면_AI_에_싣지_않고_NONE_으로_남는다() throws Exception {
        postJson("{\"experienceIds\": [" + chat + ", " + study + "], \"frame\": \"NONE\"}").andExpect(status().isAccepted());
        awaitDraft();

        assertThat(RECEIVED.getFirst()).doesNotContain("frame");
        mvc.perform(get("/api/questions/{id}", questionId)).andExpect(jsonPath("$.draft.frame").value("NONE"));
    }

    @Test
    void 실패한_초안은_틀이_남고_지침은_비어_있다() throws Exception {
        saveGuidelines("지침");
        draftReply = new FakeReply(502, "{ \"error\": { \"code\": \"LLM_ERROR\", \"message\": \"실패\" } }", 0);

        postJson("{\"experienceIds\": [" + chat + ", " + study + "], \"frame\": \"CARL\"}").andExpect(status().isAccepted());
        awaitDraft();

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.status").value("FAILED"))
                .andExpect(jsonPath("$.draft.frame").value("CARL"))
                .andExpect(jsonPath("$.draft.guidelines.length()").value(0));
    }

    @Test
    void 지침_응답이_규칙을_벗어나면_실패로_남긴다() throws Exception {
        long id = saveGuidelines("지침").getFirst();
        for (String guidelines : List.of(
                "[]",
                "[{ \"id\": %d, \"applied\": true, \"note\": null }, { \"id\": %d, \"applied\": true, \"note\": null }]".formatted(id, id),
                "[{ \"id\": %d, \"applied\": true, \"note\": null }]".formatted(id + 1000),
                "[{ \"id\": %d, \"applied\": null, \"note\": null }]".formatted(id),
                "[{ \"id\": %d, \"applied\": true, \"note\": \"있음\" }]".formatted(id),
                "[{ \"id\": %d, \"applied\": false, \"note\": null }]".formatted(id),
                "[{ \"id\": %d, \"applied\": false, \"note\": \"두\\n줄\" }]".formatted(id),
                "[{ \"id\": %d, \"applied\": false, \"note\": \"%s\" }]".formatted(id, "가".repeat(81)))) {
            draftReply = new FakeReply(200, withGuidelines(guidelines), 0);

            start(chat, study).andExpect(status().isAccepted());
            String done = awaitDraft();

            assertThat((String) JsonPath.read(done, "$.draft.error.code")).as(guidelines).isEqualTo("AI_FAILED");
            assertThat((String) JsonPath.read(done, "$.draft.error.message")).as(guidelines).contains("guidelines");
        }
    }

    @Test
    void 응답에_지침_칸이_없어도_실은_지침이_있으면_실패로_남긴다() throws Exception {
        saveGuidelines("지침");

        start(chat, study).andExpect(status().isAccepted());
        String done = awaitDraft();

        assertThat((String) JsonPath.read(done, "$.draft.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(done, "$.draft.error.message")).contains("guidelines");
    }

    @Test
    void 틀이_생기기_전_초안은_NONE_과_빈_지침이다() throws Exception {
        jdbc.update("insert into draft (question_id, status) values (?, 'COMPLETED')", questionId);

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.draft.frame").value("NONE"))
                .andExpect(jsonPath("$.draft.guidelines.length()").value(0));
    }

    @Test
    void 틀이_값_목록_밖이면_400_이고_세지_않는다() throws Exception {
        start(chat, study).andExpect(status().isAccepted());
        awaitDraft();
        int before = used();

        expectError(postJson("{\"experienceIds\": [" + chat + ", " + study + "], \"frame\": \"SWOT\"}"), 400, "INVALID_REQUEST");

        assertThat(used()).isEqualTo(before);
    }

    private String withGuidelines(String guidelines) {
        return contractExample().replaceFirst("\\{", java.util.regex.Matcher.quoteReplacement("{ \"guidelines\": " + guidelines + ","));
    }

    private List<Long> saveGuidelines(String... texts) throws Exception {
        String lines = java.util.Arrays.stream(texts).map(t -> "{\"text\": \"" + t + "\", \"source\": \"멘토 피드백\"}")
                .collect(java.util.stream.Collectors.joining(", "));
        String body = mvc.perform(post("/api/guidelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guidelines\": [" + lines + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.guidelines[*].id");
        return ids.stream().map(Number::longValue).toList();
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

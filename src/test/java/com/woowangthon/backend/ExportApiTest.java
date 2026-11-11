package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
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
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.ApplicationStatus;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionRepository;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** ETC docs/contracts/export-api.md — Markdown 내보내기 전체 · 활동 하나 · 지원 하나 (SPEC S-33, BE#118). 테스트마다 롤백한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ExportApiTest {

    static final String APP_NAME = "바꿔요 — 캠퍼스 중고거래 앱";
    static final String CHAT = "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다";

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

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
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    MockMvc mvc;
    long userId;
    long appId;
    long chatId;

    @BeforeEach
    void setUp() {
        userId = users.save(new AppUser("export-user", "hash", null)).getId();
        mvc = TestLogin.mvc(context, userId);

        // 계약 「활동 블록」 예시 그대로
        Source source = sources.save(new Source(userId, SourceKind.FILE, "바꿔요-회고.md", "회고"));
        Activity app = activities.save(new Activity(userId, APP_NAME, new Period("2025-03", "2025-06", false)));
        app.changeNote("문제만 풀다 보니 직접 서비스를 만들어 보고 싶어졌다");
        appId = app.getId();
        Experience chat = experiences.save(new Experience(app, CHAT,
                List.of(Competency.PROBLEM_SOLVING, Competency.IMPLEMENTATION), CompetenciesBy.AI, Actor.ME,
                SourceStatus.SOURCE_CONFIRMED, 0));
        chatId = chat.getId();
        citations.save(new Citation(chat, source,
                "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.", true, 5, 5));
        supplements.save(new Supplement(chat, null, null, "폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?",
                "서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다"));
        // 확인 전 「Claude 를 거친 답」 — 싣지 않는다(S-56)
        long viaClaude = supplements.save(new Supplement(chat, null, null, "Claude 가 물은 것", "Claude 를 거친 답")).getId();
        Experience api = experiences.save(new Experience(app, "백엔드 API 설계를 맡았다", List.of(Competency.IMPLEMENTATION),
                CompetenciesBy.AI, Actor.ME, SourceStatus.NEEDS_REVIEW, 1));
        citations.save(new Citation(api, source, "나는 백엔드 API 설계를 전부 맡았다.", false, null, null));
        em.flush();
        jdbc.update("update supplement set mcp_change_id = 900, mcp_client = 'claude-ai' where id = ?", viaClaude);
        em.clear();
    }

    // --- 활동 하나 ---

    @Test
    void 활동_하나는_계약_예시와_같은_글이다() throws Exception {
        MvcResult result = mvc.perform(get("/api/exports/activities/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        String body = body(result);
        assertThat(withoutExportedAt(body)).isEqualTo("""
                ---
                exportedFrom: 차곡 — 내가 우왕이 될 상인가
                scope: activity
                id: %d
                exportedAt: X
                ---

                # 바꿔요 — 캠퍼스 중고거래 앱

                %s
                2025-03 ~ 2025-06 · 경험 2개

                계기 [내가 입력]: 문제만 풀다 보니 직접 서비스를 만들어 보고 싶어졌다

                ### 채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다

                [원문 확인] · 수행: 나 · 역량 (AI 해석): 문제 해결, 기술 구현

                근거
                > 채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.
                >
                > — 바꿔요-회고.md · 5줄

                보완 [내가 입력]
                - Q. 폴링을 WebSocket 으로 바꿀 때 직접 고친 부분은 어디였나요?
                  A. 서버에 STOMP 엔드포인트를 만들고, 앱의 3초 폴링 코드를 구독 방식으로 바꿨다

                ### 백엔드 API 설계를 맡았다

                [확인 필요] · 수행: 나 · 역량 (AI 해석): 기술 구현

                근거
                > 나는 백엔드 API 설계를 전부 맡았다.
                >
                > — 바꿔요-회고.md · 원본에서 찾지 못한 발췌
                """.formatted(appId, readingGuide()));
        assertThat(body).matches("(?s).*\nexportedAt: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00\n.*");
        assertThat(body).doesNotContain("Claude 를 거친 답");
        // UTF-8 · BOM 없음 · \n
        assertThat(result.getResponse().getContentAsByteArray()[0]).isEqualTo((byte) '-');
        assertThat(body).doesNotContain("\r");
    }

    @Test
    void 파일_이름은_한글과_ASCII_대체를_같이_준다() throws Exception {
        String disposition = mvc.perform(get("/api/exports/activities/{id}", appId))
                .andReturn().getResponse().getHeader("Content-Disposition");

        assertThat(disposition)
                .matches("attachment; filename=\"chagok-activity-" + appId + "-\\d{8}\\.md\"; filename\\*=UTF-8''.+")
                // 차곡-활동-바꿔요 — 캠퍼스 중고거래 앱-
                .contains("filename*=UTF-8''%EC%B0%A8%EA%B3%A1-%ED%99%9C%EB%8F%99-%EB%B0%94%EA%BF%94%EC%9A%94%20%E2%80%94%20");
    }

    @Test
    void 교차_출처_요청이면_Content_Disposition_을_노출한다() throws Exception {
        mvc.perform(get("/api/exports/all").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", "Content-Disposition"));
    }

    @Test
    void 외부_AI_가_고친_경험은_꼬리표를_달고_지원서에서_온_경험은_출처를_단다() throws Exception {
        Application application = applications.save(new Application(userId, "OO 부트캠프"));
        long questionId = questions.save(new Question(application, "기술 문제 해결 경험")).getId();
        em.flush();
        jdbc.update("update experience set mcp_change_id = 901, mcp_client = 'claude-ai', source_status = 'NEEDS_REVIEW', "
                + "origin_question_id = ?, origin_application_id = ? where id = ?", questionId, application.getId(), chatId);
        em.clear();

        String body = body(mvc.perform(get("/api/exports/activities/{id}", appId)).andExpect(status().isOk()).andReturn());

        assertThat(body).contains("""
                [확인 필요] · 수행: 나 · 역량 (AI 해석): 문제 해결, 기술 구현 · Claude 가 고침(확인 전)
                출처: 내가 쓴 지원서 · OO 부트캠프 · 기술 문제 해결 경험
                """);
    }

    @Test
    void 경험이_없는_활동은_한_줄로_알린다() throws Exception {
        long empty = activities.save(new Activity(userId, "# 빈 활동", new Period(null, null, false))).getId();

        String body = body(mvc.perform(get("/api/exports/activities/{id}", empty)).andExpect(status().isOk()).andReturn());

        assertThat(body).contains("# \\# 빈 활동\n").endsWith("기간 모름 · 경험 0개\n\n아직 경험이 없어요\n");
    }

    // --- 전체 ---

    @Test
    void 전체는_활동을_타임라인_순서로_잇는다() throws Exception {
        activities.save(new Activity(userId, "알고리즘 스터디", new Period("2024-09", null, true)));

        String body = body(mvc.perform(get("/api/exports/all")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"chagok-all-")))
                .andReturn());

        assertThat(body).contains("scope: all\nid: null\n").contains("# 내 경험\n").contains("활동 2개 · 경험 2개\n");
        assertThat(body.indexOf("## 알고리즘 스터디")).isLessThan(body.indexOf("## " + APP_NAME));
        assertThat(body).contains("\n---\n\n## 알고리즘 스터디\n\n2024-09 ~ 진행 중 · 경험 0개\n\n아직 경험이 없어요\n");
        assertThat(body).contains("### " + CHAT);
    }

    @Test
    void 빈_계정은_빈_문서가_200() throws Exception {
        long emptyUser = users.save(new AppUser("export-empty", "hash", null)).getId();

        String body = body(TestLogin.mvc(context, emptyUser).perform(get("/api/exports/all"))
                .andExpect(status().isOk()).andReturn());

        assertThat(body).contains("# 내 경험\n").endsWith("\n아직 쌓인 활동이 없어요\n");
    }

    @Test
    void 전체에는_남의_활동이_없다() throws Exception {
        long otherUser = users.save(new AppUser("export-other", "hash", null)).getId();

        String body = body(TestLogin.mvc(context, otherUser).perform(get("/api/exports/all")).andReturn());

        assertThat(body).doesNotContain(APP_NAME);
    }

    // --- 지원 하나 ---

    @Test
    void 지원_하나는_문항마다_답_보려는_것_고른_경험을_싣는다() throws Exception {
        Application application = applications.save(new Application(userId, "OO 백엔드 부트캠프",
                OrganizationType.BOOTCAMP, JobRole.BACKEND, "https://example.com/recruit/1234"));
        application.changeStatus(ApplicationStatus.SUBMITTED);
        application.changeSubmittedMonth("2026-11");
        long done = questions.save(new Question(application, "기술적인 문제를 해결한 경험을 구체적으로 적어 주세요.")).getId();
        long failed = questions.save(new Question(application, "지원 동기를 적어 주세요.")).getId();
        long analyzing = questions.save(new Question(application, "협업 경험을 적어 주세요.")).getId();
        long drafting = questions.save(new Question(application, "#해시로 시작하는 문항")).getId();
        em.flush();
        jdbc.update("update question set status = 'COMPLETED', question_type = 'PROBLEM_SOLVING', question_type_by = 'AI', "
                + "target_length = 1000 where id = ?", done);
        jdbc.update("insert into question_requirement (question_id, position, text, status, experience_ids) values "
                + "(?, 0, '기술 문제의 원인을 찾아 해결한 경험', 'SUPPORTED', ?::bigint[]), "
                + "(?, 1, '해결한 뒤 결과를 확인한 방법', 'NOT_FOUND', '{}')", done, "{" + chatId + "}", done);
        jdbc.update("insert into question_recommendation (question_id, position, experience_id, reason, emphasis) "
                + "values (?, 0, ?, '채팅 지연이라는 기술 문제를 직접 해결한 사례라 문항과 맞는다', '강조는 싣지 않는다')", done, chatId);
        jdbc.update("insert into answer (question_id, text, confirmed_at) values (?, '「바꿔요」 프로젝트에서 해결했습니다.', "
                + "'2026-11-06T10:00:00+09:00')", done);
        jdbc.update("update question set status = 'FAILED', error_code = 'AI_TIMEOUT', error_message = 'x' where id = ?", failed);
        jdbc.update("insert into answer_draft (question_id, text, updated_at) values (?, '쓰는 중인 글입니다', now())", drafting);
        jdbc.update("update question set status = 'COMPLETED' where id = ?", drafting);
        em.clear();

        MvcResult result = mvc.perform(get("/api/exports/applications/{id}", application.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                .andReturn();
        String body = body(result);

        assertThat(withoutExportedAt(body)).startsWith("""
                ---
                exportedFrom: 차곡 — 내가 우왕이 될 상인가
                scope: application
                id: %d
                exportedAt: X
                ---

                # OO 백엔드 부트캠프 — 지원

                %s
                종류: 부트캠프 · 교육 과정 · 직무: 백엔드 · 서버 · 상태: 냈음 · 낸 달: 2026-11
                공고: https://example.com/recruit/1234

                ## 문항 1. 기술적인 문제를 해결한 경험을 구체적으로 적어 주세요.

                유형 (AI 해석): 문제 해결 · 기술적 어려움 · 목표 1000자

                ### 내가 쓴 답 — 확정 2026-11-06

                「바꿔요」 프로젝트에서 해결했습니다.

                ### 이 문항이 보려는 것 (AI 해석)

                - 기술 문제의 원인을 찾아 해결한 경험 — 근거 있음
                - 해결한 뒤 결과를 확인한 방법 — 자료에서 확인 안 됨

                ### 고른 경험

                #### 채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다

                활동: 바꿔요 — 캠퍼스 중고거래 앱 · [원문 확인] · 수행: 나 · 역량 (AI 해석): 문제 해결, 기술 구현

                추천 이유 (AI 해석): 채팅 지연이라는 기술 문제를 직접 해결한 사례라 문항과 맞는다

                근거
                > 채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.
                >
                > — 바꿔요-회고.md · 5줄
                """.formatted(application.getId(), readingGuide()));
        assertThat(body)
                .contains("## 문항 2. 지원 동기를 적어 주세요.\n\n### 내가 쓴 답\n\n아직 쓴 답이 없어요\n\n추천을 찾지 못했어요(AI_TIMEOUT)\n")
                .contains("## 문항 3. 협업 경험을 적어 주세요.\n\n### 내가 쓴 답\n\n아직 쓴 답이 없어요\n\n"
                        + "추천을 찾는 중이에요 — 끝난 뒤 다시 내보내 주세요\n")
                .contains("## 문항 4. \\#해시로 시작하는 문항\n\n### 쓰는 중인 글 — 확정 전\n\n쓰는 중인 글입니다\n")
                .doesNotContain("강조는 싣지 않는다")
                .doesNotContain("Claude 를 거친 답");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment; filename=\"chagok-application-" + application.getId() + "-");
    }

    @Test
    void 이름_없는_지원은_제목과_파일_이름을_대신한다() throws Exception {
        Application application = applications.save(new Application(userId, null));
        questions.save(new Question(application, "문항"));

        MvcResult result = mvc.perform(get("/api/exports/applications/{id}", application.getId()))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(result)).contains("\n# 이름 없는 지원\n");
        // 차곡-지원-<ID>-
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("%EC%B0%A8%EA%B3%A1-%EC%A7%80%EC%9B%90-" + application.getId() + "-");
    }

    // --- 남의 것 · 없는 것 · 로그인 ---

    @Test
    void 남의_활동과_지원은_404() throws Exception {
        long otherUser = users.save(new AppUser("export-other", "hash", null)).getId();
        Application application = applications.save(new Application(userId, "OO"));
        questions.save(new Question(application, "문항"));
        MockMvc other = TestLogin.mvc(context, otherUser);

        other.perform(get("/api/exports/activities/{id}", appId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        other.perform(get("/api/exports/applications/{id}", application.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPLICATION_NOT_FOUND"));
    }

    @Test
    void 없는_활동과_지원은_404() throws Exception {
        mvc.perform(get("/api/exports/activities/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        mvc.perform(get("/api/exports/applications/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPLICATION_NOT_FOUND"));
    }

    @Test
    void 로그인_안_했으면_401() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/exports/all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String withoutExportedAt(String body) {
        return body.replaceFirst("\nexportedAt: [^\n]+\n", "\nexportedAt: X\n");
    }

    private static String readingGuide() {
        return """
                > **읽는 법** — 이 파일은 사용자가 차곡에 쌓은 경험을 내보낸 것이다.
                > - `[원문 확인]` 원본 자료에서 확인한 경험 · `[내가 입력]` 사용자가 직접 넣거나 고친 것 · `[확인 필요]` 근거를 원본에서 찾지 못해 사용자가 아직 확인하지 않은 것 — 확인된 사실처럼 쓰지 않는다.
                > - 「근거」의 인용은 원본 발췌문이고, 「보완」은 사용자가 질문에 직접 한 답이다.
                > - `(AI 해석)` 이 붙은 것(역량 · 문항 유형 · 추천 이유 · 요구사항 판정)은 AI 가 붙인 해석이지 원문 사실이 아니다.
                > - 「팀」은 팀이 한 일, 「알 수 없음」은 누가 했는지 모르는 일이다 — 사용자 혼자 한 일처럼 바꿔 쓰지 않는다.
                > - 여기 없는 경험 · 수치는 「없다」가 아니라 「쌓인 자료에 없다」이다. 지어내지 않는다.
                """;
    }
}

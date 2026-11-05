package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.question.Answer;
import com.woowangthon.backend.question.AnswerRepository;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.JobRole;
import com.woowangthon.backend.question.OrganizationType;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionRepository;
import com.woowangthon.backend.question.QuestionType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 비슷한 확정 답 GET /api/questions/{id}/similar-answers — 유형 · 직무 · 종류가 가까운 것부터, 최대 5개. AI 를 부르지 않는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SimilarAnswerApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    QuestionRepository questions;

    @Autowired
    AnswerRepository answers;

    @Autowired
    EntityManager em;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
    }

    // --- 도우미 ---

    private static Instant at(int hour) {
        return Instant.parse("2026-10-20T00:00:00Z").plusSeconds(hour * 3600L);
    }

    private Application application(long userId, String name, OrganizationType kind, JobRole role) {
        return applications.save(new Application(userId, name, kind, role));
    }

    private Question question(Application application, String text, QuestionType type) {
        Question question = new Question(application, text);
        if (type != null) {
            question.chooseType(type);
        }
        return questions.saveAndFlush(question);
    }

    private void confirm(Question question, String text, Instant confirmedAt) {
        Answer answer = new Answer(question.getId());
        answer.confirm(text);
        Long id = answers.saveAndFlush(answer).getId();
        jdbc.update("update answer set confirmed_at = ? where id = ?", Timestamp.from(confirmedAt), id);
        em.clear(); // SQL 로 바꾼 시각을 다시 읽도록 영속성 컨텍스트를 비운다
    }

    /** 확정 답이 하나 있는 문항 — 새 지원에 만든다. */
    private long confirmed(long userId, OrganizationType kind, JobRole role, QuestionType type, Instant confirmedAt) {
        Application application = application(userId, "지원처 " + kind + role, kind, role);
        Question question = question(application, "문항 " + type, type);
        confirm(question, "답 " + question.getId(), confirmedAt);
        return question.getId();
    }

    private long base(OrganizationType kind, JobRole role, QuestionType type) {
        return question(application(TestLogin.USER_ID, "기준 지원처", kind, role), "기준 문항", type).getId();
    }

    private ResultActions similar(MockMvc client, long questionId) throws Exception {
        return client.perform(get("/api/questions/{id}/similar-answers", questionId));
    }

    private List<Long> ids(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> raw = JsonPath.read(body, "$.answers[*].questionId");
        return raw.stream().map(Number::longValue).toList();
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }

    private long otherUser() {
        return jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
    }

    // --- 테스트 ---

    @Test
    void 계약_예시_순서대로_유형_직무_종류가_가까운_것부터() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);
        // C 가 가장 최근이어도 뒤에 온다
        long c = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.MOTIVATION, at(30));
        long b = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, null, QuestionType.PROBLEM_SOLVING, at(10));
        long a = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING, at(1));

        assertThat(ids(similar(mvc, base))).containsExactly(a, b, c);

        Question qa = questions.findById(a).orElseThrow();
        similar(mvc, base)
                .andExpect(jsonPath("$.answers[0].questionId").value(a))
                .andExpect(jsonPath("$.answers[0].questionText").value("문항 PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.answers[0].questionType").value("PROBLEM_SOLVING"))
                .andExpect(jsonPath("$.answers[0].applicationId").value(qa.getApplication().getId()))
                .andExpect(jsonPath("$.answers[0].organization").value(qa.getApplication().getOrganization()))
                .andExpect(jsonPath("$.answers[0].organizationType").value("BOOTCAMP"))
                .andExpect(jsonPath("$.answers[0].jobRole").value("BACKEND"))
                .andExpect(jsonPath("$.answers[0].text").value("답 " + a))
                .andExpect(jsonPath("$.answers[0].confirmedAt").value(endsWith("+09:00")));
    }

    @Test
    void 하나도_같지_않으면_빠진다() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);
        confirmed(TestLogin.USER_ID, OrganizationType.CLUB, JobRole.FRONTEND, QuestionType.MOTIVATION, at(1));

        assertThat(ids(similar(mvc, base))).isEmpty();
    }

    @Test
    void OTHER_와_null_은_어느_것과도_같지_않다() throws Exception {
        long base = base(OrganizationType.OTHER, null, QuestionType.OTHER);
        confirmed(TestLogin.USER_ID, OrganizationType.OTHER, null, QuestionType.OTHER, at(1));

        assertThat(ids(similar(mvc, base))).isEmpty();
    }

    @Test
    void 다섯_개까지만_가까운_순서와_최근_순서로() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);
        long kindOnly = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, null, QuestionType.MOTIVATION, at(2));
        long typeOnly = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, null, QuestionType.PROBLEM_SOLVING, at(1));
        long all = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING, at(3));
        long roleOnly = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, JobRole.BACKEND, QuestionType.MOTIVATION, at(4));
        long typeKind = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, null, QuestionType.PROBLEM_SOLVING, at(5));
        long typeRole = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING, at(6));
        long roleKind = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.MOTIVATION, at(7));

        // 전체 차례: all, typeRole, typeKind, typeOnly, roleKind, roleOnly, kindOnly
        List<Long> found = ids(similar(mvc, base));
        assertThat(found).containsExactly(all, typeRole, typeKind, typeOnly, roleKind);
        assertThat(found).doesNotContain(roleOnly, kindOnly);
    }

    @Test
    void 같은_가까움이면_최근에_확정한_것이_먼저() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);
        long old = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, null, QuestionType.PROBLEM_SOLVING, at(1));
        long recent = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, null, QuestionType.PROBLEM_SOLVING, at(20));
        long middle = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, null, QuestionType.PROBLEM_SOLVING, at(10));

        assertThat(ids(similar(mvc, base))).containsExactly(recent, middle, old);
    }

    @Test
    void 자기_문항의_답은_빠지고_같은_지원의_다른_문항은_들어간다() throws Exception {
        Application application = application(TestLogin.USER_ID, "같은 지원", OrganizationType.BOOTCAMP, JobRole.BACKEND);
        Question base = question(application, "기준 문항", QuestionType.PROBLEM_SOLVING);
        confirm(base, "내 답", at(1));
        Question sibling = question(application, "같은 지원의 다른 문항", QuestionType.MOTIVATION);
        confirm(sibling, "형제 답", at(2));

        assertThat(ids(similar(mvc, base.getId()))).containsExactly(sibling.getId());
    }

    @Test
    void 확정한_답이_없으면_빈_목록() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);

        similar(mvc, base).andExpect(status().isOk()).andExpect(jsonPath("$.answers.length()").value(0));
    }

    @Test
    void 남의_문항이나_없는_문항은_404_이고_남의_답은_보이지_않는다() throws Exception {
        long other = otherUser();
        MockMvc otherMvc = TestLogin.mvc(context, other);
        long mine = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);
        long theirs = confirmed(other, OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING, at(1));

        expectError(similar(mvc, theirs), 404, "QUESTION_NOT_FOUND");
        expectError(similar(otherMvc, mine), 404, "QUESTION_NOT_FOUND");
        expectError(similar(mvc, 999999), 404, "QUESTION_NOT_FOUND");

        // 남의 확정 답은 내 목록에 나오지 않는다
        assertThat(ids(similar(mvc, mine))).isEmpty();
    }

    @Test
    void 기준_문항에_유형이_아직_없으면_직무_종류만_본다() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, null);
        confirmed(TestLogin.USER_ID, OrganizationType.CLUB, JobRole.FRONTEND, QuestionType.PROBLEM_SOLVING, at(5));
        long roleKind = confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, JobRole.BACKEND, null, at(1));
        long roleOnly = confirmed(TestLogin.USER_ID, OrganizationType.CLUB, JobRole.BACKEND, null, at(2));

        assertThat(ids(similar(mvc, base))).containsExactly(roleKind, roleOnly);
    }

    @Test
    void 사용량을_쓰지_않는다() throws Exception {
        long base = base(OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING);
        confirmed(TestLogin.USER_ID, OrganizationType.BOOTCAMP, JobRole.BACKEND, QuestionType.PROBLEM_SOLVING, at(1));
        String sql = "select count(*) || ':' || coalesce(sum(count), 0) from analysis_usage where user_id = ?";
        String before = jdbc.queryForObject(sql, String.class, TestLogin.USER_ID);

        assertThat(ids(similar(mvc, base))).hasSize(1);

        assertThat(jdbc.queryForObject(sql, String.class, TestLogin.USER_ID)).isEqualTo(before);
    }
}

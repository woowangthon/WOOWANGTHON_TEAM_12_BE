package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * ETC docs/contracts/experience-api.md 「POST /api/experiences/{keepId}/merge — 경험 합치기」 (S-70, BE#100).
 * 시드(테스트 계정): 「바꿔요」 경험 4개 · 「알고리즘 스터디」 1개. 테스트마다 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.seed-sample=true", "app.test-account.password=test-password"})
@Transactional
class ExperienceMergeApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ActivityRepository activities;

    @Autowired
    AppUserRepository users;

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
    long studyId;
    long keep; // 「바꿔요」 의 첫 경험
    long from; // 「바꿔요」 의 네 번째 경험 — 합쳐져 사라진다
    long other; // 「바꿔요」 의 두 번째 경험 — 합치기와 상관없다

    @BeforeEach
    void setUp() {
        userId = users.findByLoginId("test").orElseThrow().getId();
        mvc = TestLogin.mvc(context, userId);
        List<Activity> timeline = activities.findTimeline(userId);
        studyId = timeline.get(0).getId();
        Activity app = timeline.get(1);
        appId = app.getId();
        keep = app.getExperiences().get(0).getId();
        other = app.getExperiences().get(1).getId();
        from = app.getExperiences().get(3).getId();
    }

    // --- 근거 · 보완 ---

    @Test
    void 근거와_보완이_합쳐져_하나도_잃지_않고_지운_경험은_사라진다() throws Exception {
        String before = mvc.perform(get("/api/activities/{id}", appId)).andReturn().getResponse().getContentAsString();
        List<Integer> keepCitations = JsonPath.read(before, "$.experiences[0].citations[*].id");
        List<Integer> fromCitations = JsonPath.read(before, "$.experiences[3].citations[*].id");
        supplement(keep, "켠 쪽 보완");
        long fromSupplement = supplement(from, "지울 쪽 보완");
        em.flush();
        em.clear();

        String merged = merge(keep, from).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(keep))
                .andExpect(jsonPath("$.activityId").value(appId))
                .andExpect(jsonPath("$.supplements.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        // 근거 id 는 그대로 — 남길 쪽 것이 앞, 지울 쪽 것이 뒤(id 순)
        List<Integer> citationIds = JsonPath.read(merged, "$.citations[*].id");
        assertThat(citationIds).containsExactlyInAnyOrderElementsOf(concat(keepCitations, fromCitations));
        assertThat(citationIds).isSorted();
        List<Integer> supplementIds = JsonPath.read(merged, "$.supplements[*].id");
        assertThat(supplementIds).contains((int) fromSupplement).isSorted();

        em.flush();
        em.clear();
        mvc.perform(get("/api/activities/{id}", appId))
                .andExpect(jsonPath("$.experiences.length()").value(3))
                .andExpect(jsonPath("$.experiences[?(@.id == " + from + ")]").isEmpty());
        assertThat(count("select count(*) from experience where id = ?", from)).isZero();
    }

    @Test
    void 남길_경험의_값은_그대로다() throws Exception {
        String before = mvc.perform(get("/api/activities/{id}", appId)).andReturn().getResponse().getContentAsString();
        merge(keep, from).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value((String) JsonPath.read(before, "$.experiences[0].content")))
                .andExpect(jsonPath("$.competencies").value(JsonPath.<List<String>>read(before, "$.experiences[0].competencies")))
                .andExpect(jsonPath("$.competenciesBy").value((String) JsonPath.read(before, "$.experiences[0].competenciesBy")))
                .andExpect(jsonPath("$.actor").value((String) JsonPath.read(before, "$.experiences[0].actor")))
                .andExpect(jsonPath("$.sourceStatus").value((String) JsonPath.read(before, "$.experiences[0].sourceStatus")));
    }

    // --- 가리키던 것 ---

    @Test
    void 가리키던_곳이_남길_경험으로_바뀌고_둘이_같이_있으면_하나로_줄어든다() throws Exception {
        Application application = applications.save(new Application(userId, "OO"));
        long q1 = questions.save(new Question(application, "문항 1")).getId();
        long q2 = questions.save(new Question(application, "문항 2")).getId();

        // 추천 — q1 은 지울 쪽이 앞(그 reason 이 남는다), q2 는 지울 쪽만
        jdbc.update("insert into question_recommendation (question_id, position, experience_id, reason, emphasis) "
                + "values (?, 0, ?, '지울 쪽 이유', '지울 쪽 강조'), (?, 1, ?, '남길 쪽 이유', '남길 쪽 강조'), "
                + "(?, 0, ?, 'q2 이유', 'q2 강조')", q1, from, q1, keep, q2, from);
        long recommendation = jdbc.queryForObject(
                "select id from question_recommendation where question_id = ? and reason = '지울 쪽 이유'", Long.class, q1);
        jdbc.update("insert into question_recommendation_evidence (recommendation_id, position, type, ref_id) "
                + "values (?, 0, 'CITATION', 77)", recommendation);
        // 요구사항 — 배열에서 바뀌고 중복은 앞의 하나만
        jdbc.update("insert into question_requirement (question_id, position, text, status, experience_ids) values "
                + "(?, 0, '둘 다', 'SUPPORTED', ?::bigint[]), (?, 1, '지울 쪽만', 'PARTIAL', ?::bigint[]), "
                + "(?, 2, '없음', 'NOT_FOUND', '{}')", q1, array(from, other, keep), q1, array(other, from), q1);
        // 되묻기 — 줄이지 않는다
        jdbc.update("insert into clarification (question_id, position, experience_id, question) "
                + "values (?, 0, ?, 'a'), (?, 1, ?, 'b')", q1, from, q1, keep);
        // 초안 — 고른 경험은 앞의 하나만, 문장 근거는 EXPERIENCE 만 바꾸고 CITATION 은 그대로(번호가 같아도)
        long draft = jdbc.queryForObject(
                "insert into draft (question_id, status) values (?, 'COMPLETED') returning id", Long.class, q1);
        jdbc.update("insert into draft_experience (draft_id, position, experience_id) values (?, 0, ?), (?, 1, ?), (?, 2, ?)",
                draft, from, draft, other, draft, keep);
        jdbc.update("insert into draft_omitted (draft_id, position, text, experience_id) values (?, 0, '빈칸', ?)",
                draft, from);
        long sentence = jdbc.queryForObject(
                "insert into draft_sentence (draft_id, position, text, kind) values (?, 0, '문장', 'FACT') returning id", Long.class,
                draft);
        jdbc.update("insert into draft_sentence_evidence (sentence_id, position, type, ref_id) values "
                + "(?, 0, 'CITATION', ?), (?, 1, 'EXPERIENCE', ?), (?, 2, 'SUPPLEMENT', 5), (?, 3, 'EXPERIENCE', ?)",
                sentence, from, sentence, from, sentence, sentence, keep);
        // 근거 점검
        long check = jdbc.queryForObject("insert into grounding_check (question_id, text, status) "
                + "values (?, '글', 'COMPLETED') returning id", Long.class, q1);
        long groundingSentence = jdbc.queryForObject("insert into grounding_sentence (check_id, position, text, status) "
                + "values (?, 0, '문장', 'SOURCE') returning id", Long.class, check);
        jdbc.update("insert into grounding_sentence_evidence (sentence_id, position, type, ref_id) values "
                + "(?, 0, 'EXPERIENCE', ?), (?, 1, 'CITATION', 9)", groundingSentence, from, groundingSentence);
        // 확정 답변의 새 경험 후보
        long answer = jdbc.queryForObject("insert into answer (question_id, text, confirmed_at) "
                + "values (?, '답', now()) returning id", Long.class, q1);
        jdbc.update("insert into new_fact_candidate (answer_id, position, sentence, content, actor, status, experience_id) "
                + "values (?, 0, '문장', '내용', 'ME', 'SAVED', ?)", answer, from);
        em.flush();
        em.clear();

        merge(keep, from).andExpect(status().isOk());

        // 추천: q1 은 앞 순위(지울 쪽이던 것)만 남고 남길 경험을 가리킨다. 이유 · 근거는 그대로
        assertThat(jdbc.queryForList(
                "select experience_id || ':' || reason from question_recommendation where question_id = ?",
                String.class, q1)).containsExactly(keep + ":지울 쪽 이유");
        assertThat(count("select count(*) from question_recommendation_evidence where recommendation_id = ?",
                recommendation)).isEqualTo(1);
        assertThat(jdbc.queryForList("select experience_id from question_recommendation where question_id = ?",
                Long.class, q2)).containsExactly(keep);
        // 요구사항
        assertThat(jdbc.queryForObject("select experience_ids::text from question_requirement "
                + "where question_id = ? and position = 0", String.class, q1)).isEqualTo(array(keep, other));
        assertThat(jdbc.queryForObject("select experience_ids::text from question_requirement "
                + "where question_id = ? and position = 1", String.class, q1)).isEqualTo(array(other, keep));
        assertThat(jdbc.queryForObject("select experience_ids::text from question_requirement "
                + "where question_id = ? and position = 2", String.class, q1)).isEqualTo("{}");
        // 되묻기는 줄이지 않는다
        assertThat(jdbc.queryForList("select experience_id from clarification where question_id = ? order by position",
                Long.class, q1)).containsExactly(keep, keep);
        // 초안 — 고른 경험은 0 부터 이어지게 다시 맨다
        assertThat(jdbc.queryForList(
                "select position || ':' || experience_id from draft_experience where draft_id = ? order by position",
                String.class, draft)).containsExactly("0:" + keep, "1:" + other);
        assertThat(jdbc.queryForObject("select experience_id from draft_omitted where draft_id = ?", Long.class, draft))
                .isEqualTo(keep);
        assertThat(jdbc.queryForList("select position || ':' || type || ':' || ref_id "
                + "from draft_sentence_evidence where sentence_id = ? order by position", String.class, sentence))
                .containsExactly("0:CITATION:" + from, "1:EXPERIENCE:" + keep, "2:SUPPLEMENT:5");
        assertThat(jdbc.queryForList("select position || ':' || type || ':' || ref_id "
                + "from grounding_sentence_evidence where sentence_id = ? order by position", String.class,
                groundingSentence)).containsExactly("0:EXPERIENCE:" + keep, "1:CITATION:9");
        assertThat(jdbc.queryForObject("select experience_id from new_fact_candidate where answer_id = ?", Long.class,
                answer)).isEqualTo(keep);
    }

    // --- 저장 · 오류 ---

    @Test
    void 둘_중_하나라도_저장했으면_남길_경험이_저장된다() throws Exception {
        // 지울 쪽만 저장
        jdbc.update("update experience set saved = true where id = ?", from);
        em.flush();
        em.clear();
        merge(keep, from).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));

        // 남길 쪽만 저장 — 그대로
        jdbc.update("update experience set saved = true where id = ?", other);
        em.flush();
        em.clear();
        merge(other, keep).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
    }

    @Test
    void 둘_다_저장하지_않았으면_저장되지_않는다() throws Exception {
        merge(keep, from).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
    }

    @Test
    void 지울_경험이_없거나_같거나_다른_활동이면_400() throws Exception {
        long studyExperience = activities.findById(studyId).orElseThrow().getExperiences().get(0).getId();
        postExpecting("/api/experiences/" + keep + "/merge", "{}", 400, "INVALID_REQUEST");
        postExpecting("/api/experiences/" + keep + "/merge", "{\"fromExperienceId\": null}", 400, "INVALID_REQUEST");
        expectError(merge(keep, keep), 400, "INVALID_REQUEST");
        expectError(merge(keep, studyExperience), 400, "INVALID_REQUEST");

        // 하나도 바뀌지 않았다
        assertThat(count("select count(*) from experience where id in (?, ?)", keep, studyExperience)).isEqualTo(2);
    }

    @Test
    void 없는_경험과_남의_경험은_404_이고_바뀌지_않는다() throws Exception {
        expectError(merge(999999L, from), 404, "EXPERIENCE_NOT_FOUND");
        expectError(merge(keep, 999999L), 404, "EXPERIENCE_NOT_FOUND");

        long otherUserId = users.saveAndFlush(new AppUser("merge-other", "hash", null)).getId();
        expectError(TestLogin.mvc(context, otherUserId).perform(post("/api/experiences/{id}/merge", keep)
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromExperienceId\": " + from + "}")), 404,
                "EXPERIENCE_NOT_FOUND");

        assertThat(count("select count(*) from experience where id in (?, ?)", keep, from)).isEqualTo(2);
    }

    @Test
    void 이미_합쳐진_경험을_또_합치면_404() throws Exception {
        merge(keep, from).andExpect(status().isOk());
        expectError(merge(keep, from), 404, "EXPERIENCE_NOT_FOUND");
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        expectError(MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/experiences/{id}/merge", keep).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromExperienceId\": " + from + "}")), 401, "UNAUTHENTICATED");
    }

    // --- 도우미 ---

    private ResultActions merge(long keepId, long fromId) throws Exception {
        return mvc.perform(post("/api/experiences/{id}/merge", keepId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromExperienceId\": " + fromId + "}"));
    }

    private void postExpecting(String url, String body, int expectedStatus, String code) throws Exception {
        expectError(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)), expectedStatus, code);
    }

    private static void expectError(ResultActions result, int expectedStatus, String code) throws Exception {
        result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.error.code").value(code));
    }

    private long supplement(long experienceId, String answer) {
        return jdbc.queryForObject("insert into supplement (experience_id, question, answer) values (?, '질문', ?) "
                + "returning id", Long.class, experienceId, answer);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String array(long... ids) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < ids.length; i++) {
            text.append(i == 0 ? "" : ",").append(ids[i]);
        }
        return text.append('}').toString();
    }

    private static List<Integer> concat(List<Integer> first, List<Integer> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}

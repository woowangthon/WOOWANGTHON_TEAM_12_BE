package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.question.Application;
import com.woowangthon.backend.question.ApplicationRepository;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** ETC docs/contracts/question-api.md 「임시 글」 — PUT /api/questions/{id}/answer-draft (SPEC S-72). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AnswerDraftApiTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    QuestionRepository questions;

    MockMvc mvc;

    long questionId;

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
        questionId = question(TestLogin.USER_ID);
    }

    private long question(long userId) {
        Application application = applications.save(new Application(userId, "OO 백엔드 부트캠프"));
        return questions.saveAndFlush(new Question(application, "기술적인 문제를 해결한 경험을 적어 주세요.")).getId();
    }

    private ResultActions saveDraft(MockMvc client, long id, String body) throws Exception {
        return client.perform(put("/api/questions/{id}/answer-draft", id)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private int usage() {
        return jdbc.queryForObject("select count(*) from analysis_usage", Integer.class);
    }

    @Test
    void 저장하면_문항_응답에_실리고_다시_저장하면_통째로_바뀐다() throws Exception {
        int before = usage();
        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerDraft").value(nullValue()));

        saveDraft(mvc, questionId, """
                { "text": "쓰는 중", "state": { "checkedSentences": ["쓰는 중"] } }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerDraft.text").value("쓰는 중"))
                .andExpect(jsonPath("$.answerDraft.state.checkedSentences[0]").value("쓰는 중"))
                .andExpect(jsonPath("$.answerDraft.updatedAt").isString());

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.answerDraft.text").value("쓰는 중"))
                .andExpect(jsonPath("$.answerDraft.state.checkedSentences[0]").value("쓰는 중"))
                .andExpect(jsonPath("$.answer").value(nullValue()));

        // state 를 빼면 null 로, 빈 글도 저장한다
        saveDraft(mvc, questionId, "{ \"text\": \"\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerDraft.text").value(""))
                .andExpect(jsonPath("$.answerDraft.state").value(nullValue()));
        assertThat(jdbc.queryForObject("select count(*) from answer_draft where question_id = ?", Integer.class,
                questionId)).isEqualTo(1);
        // AI 를 부르지 않고 분석 횟수를 쓰지 않는다
        assertThat(usage()).isEqualTo(before);
    }

    @Test
    void 확정하면_임시_글을_지우고_임시_글은_확정_답이_아니다() throws Exception {
        saveDraft(mvc, questionId, "{ \"text\": \"쓰는 중\" }").andExpect(status().isOk());
        mvc.perform(put("/api/questions/{id}/answer", questionId)
                .contentType(MediaType.APPLICATION_JSON).content("{ \"text\": \"다 쓴 답\" }"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/questions/{id}", questionId))
                .andExpect(jsonPath("$.answer.text").value("다 쓴 답"))
                .andExpect(jsonPath("$.answerDraft").value(nullValue()));

        // 다른 문항의 임시 글은 비슷한 확정 답변에 나오지 않는다
        long other = question(TestLogin.USER_ID);
        saveDraft(mvc, other, "{ \"text\": \"다른 문항 임시 글\" }").andExpect(status().isOk());
        mvc.perform(get("/api/questions/{id}/similar-answers", questionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers.length()").value(0));
    }

    @Test
    void 잘못된_요청() throws Exception {
        expectError(saveDraft(mvc, questionId, "{ \"state\": {} }"), 400, "INVALID_REQUEST");
        expectError(saveDraft(mvc, questionId, "{ \"text\": \"a\", \"state\": [1] }"), 400, "INVALID_REQUEST");
        expectError(saveDraft(mvc, questionId, "{ \"text\": \"a\", \"state\": \"s\" }"), 400, "INVALID_REQUEST");
        expectError(saveDraft(mvc, questionId, "{ \"text\": \"" + "가".repeat(5001) + "\" }"), 413, "TEXT_TOO_LONG");
        expectError(saveDraft(mvc, questionId, "{ \"text\": \"a\", \"state\": { \"s\": \"" + "가".repeat(50_000) + "\" } }"),
                413, "TEXT_TOO_LONG");
        // 5000자 · state null 은 된다
        saveDraft(mvc, questionId, "{ \"text\": \"" + "가".repeat(5000) + "\", \"state\": null }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerDraft.state").value(nullValue()));
    }

    @Test
    void 남의_문항이나_없는_문항은_404() throws Exception {
        long otherUser = jdbc.queryForObject("insert into app_user (name) values ('다른 사용자') returning id", Long.class);
        long theirs = question(otherUser);
        expectError(saveDraft(mvc, theirs, "{ \"text\": \"a\" }"), 404, "QUESTION_NOT_FOUND");
        expectError(saveDraft(mvc, 999_999, "{ \"text\": \"a\" }"), 404, "QUESTION_NOT_FOUND");

        // 주인이 저장한 임시 글은 남이 읽을 수 없다
        saveDraft(TestLogin.mvc(context, otherUser), theirs, "{ \"text\": \"남의 글\" }").andExpect(status().isOk());
        expectError(mvc.perform(get("/api/questions/{id}", theirs)), 404, "QUESTION_NOT_FOUND");
    }
}

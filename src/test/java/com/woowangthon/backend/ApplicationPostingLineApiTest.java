package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.woowangthon.backend.question.MatchRunner;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** S-18 · S-91: 영속성 · 소유자 격리 · 검증과 분석 횟수의 원자성. AI 매칭은 별도 테스트에서 검증한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ApplicationPostingLineApiTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean MatchRunner matches;
    MockMvc mvc;

    static final String LINE = """
            {"section":"REQUIRED","text":"  Spring API 개발 경험  ","ksa":"SKILL","readFrom":"IMAGE"}
            """.strip();

    @BeforeEach
    void setUp() {
        mvc = TestLogin.mvc(context);
    }

    private ResultActions submit(String questions, String lines) throws Exception {
        return mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"questions\":" + questions + ",\"postingLines\":" + lines + "}"));
    }

    private long create(String questions, String lines) throws Exception {
        String body = submit(questions, lines).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.<Number>read(body, "$.applicationId").longValue();
    }

    private int usage() {
        return jdbc.queryForObject("select coalesce(sum(count),0) from analysis_usage where user_id = ?",
                Integer.class, TestLogin.USER_ID);
    }

    @Test
    void 문항_없이_공고_줄만_순서와_값_그대로_저장하고_목록과_단건에_돌려준다() throws Exception {
        int used = usage();
        long id = create("[]", "[" + LINE + "," + LINE.replace("REQUIRED", "DUTY").replace("SKILL", "ATTITUDE") + "]");
        em.flush();
        em.clear();

        mvc.perform(get("/api/applications/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.questions").isEmpty())
                .andExpect(jsonPath("$.postingLines.length()").value(2))
                .andExpect(jsonPath("$.postingLines[0].id").isNumber())
                .andExpect(jsonPath("$.postingLines[0].text").value("  Spring API 개발 경험  "))
                .andExpect(jsonPath("$.postingLines[0].section").value("REQUIRED"))
                .andExpect(jsonPath("$.postingLines[1].section").value("DUTY"))
                .andExpect(jsonPath("$.postingLines[1].ksa").value("ATTITUDE"))
                .andExpect(jsonPath("$.postingLines[0].readFrom").value("IMAGE"));
        mvc.perform(get("/api/applications"))
                .andExpect(jsonPath("$.applications[0].postingLines.length()").value(2));
        assertThat(usage()).isEqualTo(used);
        assertThat(jdbc.queryForList("select position from application_posting_line where application_id=? order by position",
                Integer.class, id)).containsExactly(0, 1);
    }

    @Test
    void 다른_사용자는_공고_줄이_있는_지원도_읽거나_지우지_못한다() throws Exception {
        long id = create("[]", "[" + LINE + "]");
        MockMvc other = TestLogin.mvc(context, 999999L);
        other.perform(get("/api/applications/{id}", id)).andExpect(status().isNotFound());
        other.perform(get("/api/applications")).andExpect(jsonPath("$.applications").isEmpty());
        other.perform(delete("/api/applications/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications/{id}", id)).andExpect(jsonPath("$.postingLines.length()").value(1));
    }

    @Test
    void 지원을_지우면_요건_줄도_지우고_문항_0개_지원에_문항을_더할_수_있다() throws Exception {
        int used = usage();
        long id = create("[]", "[" + LINE + "]");
        mvc.perform(post("/api/applications/{id}/questions", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"문제 해결 경험\"}")).andExpect(status().isAccepted());
        assertThat(usage()).isEqualTo(used + 1);
        em.flush();
        em.clear();
        mvc.perform(delete("/api/applications/{id}", id)).andExpect(status().isNoContent());
        em.flush();
        assertThat(jdbc.queryForObject("select count(*) from application_posting_line where application_id=?", Integer.class, id)).isZero();
        mvc.perform(get("/api/applications/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void 요건_40줄_300자와_null_분류를_받고_분석은_문항_수만큼만_센다() throws Exception {
        String maxLine = LINE.replace("  Spring API 개발 경험  ", "가".repeat(300)).replace("\"SKILL\"", "null");
        int used = usage();
        long id = create("[{\"text\":\"문항 하나\"},{\"text\":\"문항 둘\"}]",
                "[" + (maxLine + ",").repeat(39) + maxLine + "]");
        assertThat(usage()).isEqualTo(used + 2);
        mvc.perform(get("/api/applications/{id}", id)).andExpect(jsonPath("$.postingLines.length()").value(40));
    }

    @Test
    void 잘못된_줄과_둘_다_빈_요청은_저장도_차감도_하지_않는다() throws Exception {
        int used = usage();
        int count = jdbc.queryForObject("select count(*) from application", Integer.class);
        for (String lines : List.of("[]", "[null]", "[{}]", "[" + (LINE + ",").repeat(40) + LINE + "]",
                "[" + LINE.replace("  Spring API 개발 경험  ", " ") + "]",
                "[" + LINE.replace("  Spring API 개발 경험  ", "가".repeat(301)) + "]",
                "[" + LINE.replace("REQUIRED", "OTHER") + "]", "[" + LINE.replace("SKILL", "OTHER") + "]",
                "[" + LINE.replace("IMAGE", "OTHER") + "]", "[" + LINE.replace("\"REQUIRED\"", "null") + "]",
                "[" + LINE.replace("\"IMAGE\"", "null") + "]")) {
            submit("[]", lines).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(usage()).isEqualTo(used);
        assertThat(jdbc.queryForObject("select count(*) from application", Integer.class)).isEqualTo(count);
    }

    @Test
    void 분석_잔여_횟수가_모자라면_공고_줄도_지원도_남기지_않는다() throws Exception {
        jdbc.update("update app_user set daily_analysis_limit=1 where id=?", TestLogin.USER_ID);
        int count = jdbc.queryForObject("select count(*) from application", Integer.class);
        int lines = jdbc.queryForObject("select count(*) from application_posting_line", Integer.class);
        submit("[{\"text\":\"문항 하나\"},{\"text\":\"문항 둘\"}]", "[" + LINE + "]")
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("select count(*) from application", Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from application_posting_line", Integer.class)).isEqualTo(lines);
    }
}

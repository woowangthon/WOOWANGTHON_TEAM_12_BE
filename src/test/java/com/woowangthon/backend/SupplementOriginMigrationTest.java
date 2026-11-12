package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** V48 — 기존 STAR 보완만 STAR 로 채우고 되묻기 · 외부 AI 답은 구분한다(BE#190). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SupplementOriginMigrationTest {

    private static final String SCHEMA = "migration_v48_test";

    @Autowired DataSource dataSource;

    @Test
    void 기존_보완의_입구를_채운다() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway("47").migrate();
        try {
            jdbc.update("insert into " + SCHEMA + ".activity (id, user_id, name) values (1, 1, '활동')");
            jdbc.update("insert into " + SCHEMA + ".experience "
                    + "(id, activity_id, content, actor, source_status, competencies_by, position) "
                    + "values (1, 1, '경험', 'ME', 'USER_ENTERED', 'USER', 0)");
            jdbc.update("insert into " + SCHEMA + ".application (id, user_id) values (1, 1)");
            jdbc.update("insert into " + SCHEMA + ".question (id, user_id, application_id, text, status) "
                    + "values (1, 1, 1, '문항', 'COMPLETED')");
            jdbc.update("insert into " + SCHEMA + ".clarification "
                    + "(id, question_id, position, experience_id, question) values (1, 1, 0, 1, '질문')");
            jdbc.update("insert into " + SCHEMA + ".supplement "
                    + "(id, experience_id, clarification_id, question_id, question, answer, mcp_change_id) values "
                    + "(1, 1, null, null, '칸 질문', '칸 답', null), "
                    + "(2, 1, 1, 1, '되묻기', '답', null), "
                    + "(3, 1, null, 1, '지워진 되묻기', '답', null), "
                    + "(4, 1, null, null, '외부 AI 질문', '답', 9001)");

            flyway("latest").migrate();

            assertThat(jdbc.queryForList("select origin from " + SCHEMA + ".supplement order by id", String.class))
                    .containsExactly("STAR", "CLARIFICATION", "CLARIFICATION", "EXTERNAL_AI");
            // 연결이 나중에 사라져도 출처는 바뀌지 않는다.
            jdbc.update("update " + SCHEMA + ".supplement set clarification_id = null, question_id = null, "
                    + "mcp_change_id = null");
            assertThat(jdbc.queryForList("select origin from " + SCHEMA + ".supplement order by id", String.class))
                    .containsExactly("STAR", "CLARIFICATION", "CLARIFICATION", "EXTERNAL_AI");
        } finally {
            jdbc.execute("drop schema " + SCHEMA + " cascade");
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}

package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V4 — 이미 있는 경험에 역량 출처(competencies_by)가 채워지는지(BE#25).
 * 근거가 있거나 「내가 입력」이 아니면 AI, 근거 없는 「내가 입력」(직접 추가)만 USER.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CompetenciesByMigrationTest {

    private static final String SCHEMA = "migration_v4_test";

    @Autowired
    DataSource dataSource;

    @Test
    void 근거_없는_내가_입력만_USER_나머지는_AI() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");

        flyway("3").migrate();
        jdbc.update("insert into " + SCHEMA + ".source (id, user_id, kind, name, text) values (1, 1, 'TEXT', '원본', '본문')");
        jdbc.update("insert into " + SCHEMA + ".activity (id, user_id, name) values (1, 1, '활동')");
        String insert = "insert into " + SCHEMA + ".experience (id, activity_id, content, actor, source_status, position)"
                + " values (?, 1, '경험', 'ME', ?, ?)";
        List<String> statuses = List.of("SOURCE_CONFIRMED", "NEEDS_REVIEW", "USER_ENTERED", "USER_ENTERED");
        for (int i = 0; i < statuses.size(); i++) {
            jdbc.update(insert, i + 1, statuses.get(i), i);
        }
        // 3번: 분석으로 만든 뒤 본문만 고친 경험 — 근거가 남아 있다
        jdbc.update("insert into " + SCHEMA + ".citation (experience_id, source_id, quote) values (3, 1, '발췌')");

        flyway("latest").migrate();

        List<String> migrated = jdbc.queryForList(
                "select competencies_by from " + SCHEMA + ".experience order by id", String.class);
        assertThat(migrated).containsExactly("AI", "AI", "AI", "USER");
        jdbc.execute("drop schema " + SCHEMA + " cascade");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}

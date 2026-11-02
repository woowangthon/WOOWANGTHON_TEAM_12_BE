package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3 — 있던 관점(perspective)이 역량 배열(competencies)로 옮겨지는지(BE#15).
 * 앱 스키마와 떨어진 스키마에 V2 까지 올리고 데이터를 넣은 뒤 끝까지 올린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CompetencyMigrationTest {

    private static final String SCHEMA = "migration_v3_test";

    @Autowired
    DataSource dataSource;

    @Test
    void 목록에_맞는_관점만_역량으로_옮기고_나머지는_빈_배열() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");

        flyway("2").migrate();
        jdbc.update("insert into " + SCHEMA + ".activity (id, user_id, name) values (1, 1, '활동')");
        String insert = "insert into " + SCHEMA + ".experience"
                + " (id, activity_id, content, perspective, actor, source_status, position)"
                + " values (?, 1, '경험', ?, 'ME', 'NEEDS_REVIEW', ?)";
        List<String> perspectives = Arrays.asList("기술 구현", "배운 점", " 협업 ", "갈등 해결", null);
        for (int i = 0; i < perspectives.size(); i++) {
            jdbc.update(insert, i + 1, perspectives.get(i), i);
        }

        flyway("latest").migrate();

        List<String> migrated = jdbc.queryForList(
                "select array_to_string(competencies, ',') from " + SCHEMA + ".experience order by id", String.class);
        assertThat(migrated).containsExactly("IMPLEMENTATION", "LEARNING", "COLLABORATION", "", "");
        jdbc.execute("drop schema " + SCHEMA + " cascade");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}

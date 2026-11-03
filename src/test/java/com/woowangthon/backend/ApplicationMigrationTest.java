package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** V8 — 이미 저장된 문항(BE#22 이후)마다 지원이 하나씩 생기고, 지원처 이름이 지원으로 옮겨지는지(BE#30). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ApplicationMigrationTest {

    private static final String SCHEMA = "migration_v8_test";

    @Autowired
    DataSource dataSource;

    @Test
    void 문항마다_지원이_하나씩_생기고_새_지원은_그_뒤_ID_를_받는다() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");

        flyway("7").migrate();
        String insert = "insert into " + SCHEMA + ".question (id, user_id, organization, text, status, created_at)"
                + " values (?, 1, ?, ?, 'COMPLETED', ?::timestamptz)";
        jdbc.update(insert, 3, "OO 백엔드 부트캠프", "기술 문제", "2026-11-04T10:00:00+09:00");
        jdbc.update(insert, 5, null, "역할 · 협업", "2026-11-04T10:20:00+09:00");

        flyway("latest").migrate();

        List<Map<String, Object>> rows = jdbc.queryForList("select q.id, q.application_id, a.user_id, a.organization,"
                + " a.created_at = q.created_at as same_time from " + SCHEMA + ".question q join " + SCHEMA
                + ".application a on a.id = q.application_id order by q.id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("application_id", 3L).containsEntry("organization", "OO 백엔드 부트캠프")
                .containsEntry("user_id", 1L).containsEntry("same_time", true);
        assertThat(rows.get(1)).containsEntry("application_id", 5L).containsEntry("organization", null);

        Long next = jdbc.queryForObject("insert into " + SCHEMA + ".application (user_id) values (1) returning id",
                Long.class);
        assertThat(next).isEqualTo(6L);
        jdbc.execute("drop schema " + SCHEMA + " cascade");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }
}

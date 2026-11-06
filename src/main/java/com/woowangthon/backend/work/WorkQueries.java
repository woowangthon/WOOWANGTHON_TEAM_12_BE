package com.woowangthon.backend.work;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 다섯 표에서 사용자의 일을 읽는다 — 표마다 조회해 코드에서 합친다(BE#77). 폴링으로 자주 불려 문항 · 부탁 글은 앞부분만 읽는다.
 * 재시도는 같은 행을 다시 쓰므로, 분석 · 추천이 도는 동안의 시작 시각은 updated_at 이다.
 */
@Repository
class WorkQueries {

    // 라벨(최대 100자)을 다듬기 전에 읽을 만큼 — 공백을 줄여도 100자가 남게 조금 더 읽는다
    private static final String LABEL_HEAD = "120";
    private static final String QUESTION_LABEL = "concat_ws(' · ', a.organization, left(q.text, " + LABEL_HEAD + "))";

    private static final String SOURCE_ANALYSIS = """
            select j.id, j.status, left(s.name, %s) as label, j.error_code, s.id as source_id,
                   null::bigint as application_id, null::bigint as question_id,
                   case when j.status = 'ANALYZING' then j.updated_at else j.created_at end as started_at,
                   case when j.status not in ('QUEUED', 'ANALYZING') then j.updated_at end as finished_at
            from analysis_job j
            join source s on s.id = j.source_id
            where j.user_id = ? and (j.status in ('QUEUED', 'ANALYZING') or j.updated_at >= ?)
            """.formatted(LABEL_HEAD);

    private static final String MATCH = """
            select q.id, q.status, %s as label, q.error_code, null::bigint as source_id,
                   q.application_id, q.id as question_id,
                   case when q.status = 'ANALYZING' then q.updated_at else q.created_at end as started_at,
                   q.finished_at
            from question q
            join application a on a.id = q.application_id
            where q.user_id = ? and (q.status = 'ANALYZING' or q.finished_at >= ?)
            """.formatted(QUESTION_LABEL);

    // 근거 점검 · 초안은 문항마다 한 행이고 다시 시작하면 created_at 도 새로 쓴다. 사용자는 문항을 거쳐 찾는다
    private static final String QUESTION_WORK = """
            select w.id, w.status, %s as label, w.error_code, null::bigint as source_id,
                   q.application_id, q.id as question_id, w.created_at as started_at,
                   case when w.status <> 'ANALYZING' then w.updated_at end as finished_at
            from %s w
            join question q on q.id = w.question_id
            join application a on a.id = q.application_id
            where q.user_id = ? and (w.status = 'ANALYZING' or w.updated_at >= ?)
            """;

    // 지워진 문항을 가리킬 수 있어(외래 키 없음) left join — 그러면 applicationId 는 null
    private static final String CHAGOK = """
            select r.id, r.status, left(r.text, %s) as label, r.error_code, null::bigint as source_id,
                   q.application_id, r.question_id, r.created_at as started_at, r.finished_at
            from chagok_run r
            left join question q on q.id = r.question_id
            where r.user_id = ? and (r.status = 'RUNNING'
                or (r.status = 'WAITING_CONFIRM' and r.created_at >= ?)
                or (r.status in ('DONE', 'FAILED') and r.finished_at >= ?))
            """.formatted(LABEL_HEAD);

    private final JdbcTemplate jdbc;

    WorkQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 사용자의 진행 중인 일과 since 뒤에 끝난 일. 확인을 기다리는 차곡이 일은 since 뒤에 시작한 것만. */
    List<WorkRow> find(long userId, OffsetDateTime since) {
        List<WorkRow> rows = new ArrayList<>();
        rows.addAll(read(WorkKind.SOURCE_ANALYSIS, SOURCE_ANALYSIS, userId, since));
        rows.addAll(read(WorkKind.MATCH, MATCH, userId, since));
        rows.addAll(read(WorkKind.GROUNDING_CHECK, QUESTION_WORK.formatted(QUESTION_LABEL, "grounding_check"), userId, since));
        rows.addAll(read(WorkKind.DRAFT, QUESTION_WORK.formatted(QUESTION_LABEL, "draft"), userId, since));
        rows.addAll(read(WorkKind.CHAGOK, CHAGOK, userId, since, since));
        return rows;
    }

    private List<WorkRow> read(WorkKind kind, String sql, Object... params) {
        return jdbc.query(sql, (rs, n) -> row(kind, rs), params);
    }

    private static WorkRow row(WorkKind kind, ResultSet rs) throws SQLException {
        return new WorkRow(kind, rs.getLong("id"), rs.getString("status"), rs.getString("label"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                rs.getString("error_code"), rs.getObject("source_id", Long.class),
                rs.getObject("application_id", Long.class), rs.getObject("question_id", Long.class));
    }
}

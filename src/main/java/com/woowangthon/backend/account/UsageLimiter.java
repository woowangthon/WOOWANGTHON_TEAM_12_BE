package com.woowangthon.backend.account;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자별 하루 분석 횟수 한도(S-15, ADR-004 「한도 세 겹」의 첫째). 하루는 한국 날짜로 센다.
 * AI 를 부르는 일(자료 넣기 · 분석 다시 시도 · 문항 넣기 · 문항 다시 시도 · 꺼내기 등 auth-api.md 목록)을 시작할 때마다 1, 지원 넣기는 문항마다 1
 * — 실패한 분석도 센다(LLM 은 이미 불렸을 수 있다).
 */
@Service
public class UsageLimiter {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final int defaultLimit;

    public UsageLimiter(JdbcTemplate jdbc, AppUserRepository users,
            @Value("${app.limit.daily-analyses}") int defaultLimit) {
        this.jdbc = jdbc;
        this.users = users;
        this.defaultLimit = defaultLimit;
    }

    public record Usage(int used, int limit) {
    }

    /**
     * 오늘 횟수를 하나 쓴다. 다 썼으면 429 DAILY_LIMIT_EXCEEDED — 분석을 시작하지 않는다.
     * 부르는 쪽 트랜잭션에 묶여서, 그 뒤 검사에 걸려 롤백되면 횟수도 돌아온다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(long userId) {
        consume(userId, 1);
    }

    /**
     * 오늘 횟수를 count 만큼 한꺼번에 쓴다(지원 넣기 — 문항마다 1). 남은 횟수가 count 보다 적으면 하나도 쓰지 않고 429 —
     * 일부만 넣지 않는다(question-api.md 「POST /api/applications」).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(long userId, int count) {
        int limit = limitOf(userId);
        // 한도 안일 때만 더한다 — 동시에 눌러도 행 잠금으로 한도를 넘지 않는다
        List<Integer> counted = count <= 0 || count > limit ? List.of() : jdbc.queryForList("""
                insert into analysis_usage (user_id, day, count) values (?, ?, ?)
                on conflict (user_id, day) do update set count = analysis_usage.count + excluded.count
                where analysis_usage.count + excluded.count <= ?
                returning count
                """, Integer.class, userId, Date.valueOf(today()), count, limit);
        if (counted.isEmpty()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT_EXCEEDED",
                    "오늘 분석 횟수(" + limit + "회)를 다 썼습니다. 한국 시간 자정에 다시 채워집니다");
        }
    }

    @Transactional(readOnly = true)
    public Usage today(long userId) {
        List<Integer> used = jdbc.queryForList("select count from analysis_usage where user_id = ? and day = ?",
                Integer.class, userId, Date.valueOf(today()));
        return new Usage(used.isEmpty() ? 0 : used.getFirst(), limitOf(userId));
    }

    private int limitOf(long userId) {
        Integer own = users.findById(userId).map(AppUser::getDailyAnalysisLimit).orElse(null);
        return own == null ? defaultLimit : own;
    }

    private static LocalDate today() {
        return LocalDate.now(Times.SEOUL);
    }
}

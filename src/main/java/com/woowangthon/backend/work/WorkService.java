package com.woowangthon.backend.work;

import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.Times;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 중인 일과 막 끝난 일(ETC docs/contracts/work-api.md · SPEC S-47). 일의 상태는 바꾸지 않고, AI 를 부르지 않아 한도도 세지 않는다.
 * 안 읽은 소식은 FE 가 seenAt 과 finishedAt 을 비교해 센다.
 */
@Service
public class WorkService {

    private static final int RECENT_HOURS = 24;
    private static final int MAX_RECENT = 20;
    private static final int MAX_LABEL_LENGTH = 100;

    private final WorkQueries queries;
    private final AppUserRepository users;

    public WorkService(WorkQueries queries, AppUserRepository users) {
        this.queries = queries;
        this.users = users;
    }

    public record Work(OffsetDateTime seenAt, List<Item> inProgress, List<Item> recent) {
    }

    public record Item(String key, WorkKind kind, String status, String label, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, String errorCode, Long sourceId, Long jobId, Long applicationId, Long questionId,
            Long runId) {
    }

    public record Seen(OffsetDateTime seenAt) {
    }

    @Transactional(readOnly = true)
    public Work get() {
        long userId = CurrentUser.id();
        List<WorkRow> rows = queries.find(userId, OffsetDateTime.now().minusHours(RECENT_HOURS));
        return new Work(seenAt(userId),
                sorted(rows.stream().filter(r -> r.finishedAt() == null).toList(), WorkRow::startedAt),
                sorted(rows.stream().filter(r -> r.finishedAt() != null).toList(), WorkRow::finishedAt)
                        .stream().limit(MAX_RECENT).toList());
    }

    /** 앞으로만 간다 — 저장된 값보다 이르면 그대로 두고 저장된 값을 돌려준다. */
    @Transactional
    public Seen markSeen(Seen request) {
        if (request.seenAt() == null) {
            throw ApiException.invalidRequest("seenAt 이 없습니다");
        }
        long userId = CurrentUser.id();
        users.advanceWorkSeenAt(userId, request.seenAt());
        return new Seen(seenAt(userId));
    }

    private OffsetDateTime seenAt(long userId) {
        OffsetDateTime seenAt = users.findById(userId).orElseThrow(CurrentUser::unauthenticated).getWorkSeenAt();
        return seenAt == null ? null : Times.seoul(seenAt);
    }

    private static List<Item> sorted(List<WorkRow> rows, Function<WorkRow, OffsetDateTime> time) {
        return rows.stream().sorted(Comparator.comparing(time).reversed()).map(WorkService::item).toList();
    }

    private static Item item(WorkRow row) {
        boolean source = row.kind() == WorkKind.SOURCE_ANALYSIS;
        boolean chagok = row.kind() == WorkKind.CHAGOK;
        return new Item(row.kind() + ":" + row.id(), row.kind(), row.status(), label(row.label()),
                Times.seoul(row.startedAt()), row.finishedAt() == null ? null : Times.seoul(row.finishedAt()),
                "FAILED".equals(row.status()) ? row.errorCode() : null, row.sourceId(), source ? row.id() : null,
                row.applicationId(), row.questionId(), chagok ? row.id() : null);
    }

    // 알아볼 한 줄 — 줄바꿈을 없애고, 이모지가 반으로 잘리지 않게 글자 단위로 자른다
    private static String label(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.codePointCount(0, line.length()) <= MAX_LABEL_LENGTH ? line
                : line.substring(0, line.offsetByCodePoints(0, MAX_LABEL_LENGTH));
    }
}

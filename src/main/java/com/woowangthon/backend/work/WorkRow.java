package com.woowangthon.backend.work;

import java.time.OffsetDateTime;

/** 표에서 읽은 일 하나 — 응답 모양으로 다듬기 전. finishedAt 이 null 이면 진행 중이다. */
record WorkRow(WorkKind kind, long id, String status, String label, OffsetDateTime startedAt, OffsetDateTime finishedAt,
        String errorCode, Long sourceId, Long applicationId, Long questionId) {
}

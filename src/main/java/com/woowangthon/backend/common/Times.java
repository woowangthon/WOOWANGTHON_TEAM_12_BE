package com.woowangthon.backend.common;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 응답 시각 — 계약 예시처럼 "2026-11-03T14:05:00+09:00" 으로 내보낸다. */
public final class Times {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private Times() {
    }

    public static OffsetDateTime seoul(OffsetDateTime time) {
        return time.atZoneSameInstant(SEOUL).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
    }
}

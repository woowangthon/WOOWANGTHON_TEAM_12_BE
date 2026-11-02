package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * 기간 — 계약 README 「기간」. start · end 는 "YYYY" · "YYYY-MM" · "YYYY-MM-DD" 중 하나이거나 null.
 * 원본에 있는 만큼만 적으므로 정밀도가 서로 달라도 된다.
 */
public record Period(String start, String end, boolean ongoing) {

    private static final Pattern FORMAT = Pattern.compile("\\d{4}(-(0[1-9]|1[0-2])(-\\d{2})?)?");

    /** 계약 규칙을 어기면 INVALID_REQUEST(형식) · INVALID_PERIOD(앞뒤 · 진행 중) 를 던진다. */
    public static Period validated(String start, String end, boolean ongoing) {
        checkFormat("start", start);
        checkFormat("end", end);
        if (ongoing && end != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "진행 중인 활동은 end 가 없어야 합니다");
        }
        if (start != null && end != null && startsAfter(start, end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "start 가 end 보다 늦습니다");
        }
        return new Period(start, end, ongoing);
    }

    private static void checkFormat(String name, String value) {
        if (value == null) {
            return;
        }
        boolean ok = FORMAT.matcher(value).matches();
        if (ok && value.length() == 10) {
            try {
                LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                ok = false;
            }
        }
        if (!ok) {
            throw ApiException.invalidRequest(name + " 는 YYYY · YYYY-MM · YYYY-MM-DD 중 하나여야 합니다");
        }
    }

    // "2025-03" ~ "2025" 처럼 정밀도가 다르면 짧은 쪽 길이만큼만 비교한다(같은 해 안이면 늦은 게 아니다)
    private static boolean startsAfter(String start, String end) {
        int length = Math.min(start.length(), end.length());
        return start.substring(0, length).compareTo(end.substring(0, length)) > 0;
    }
}

package com.woowangthon.backend.export;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 내려받을 파일 이름(export-api.md 「파일 이름」) — 한글 `차곡-<대상>-<이름>-<YYYYMMDD>.md` 와 ASCII 대체
 * `chagok-<대상>-<ID>-<YYYYMMDD>.md`. 날짜는 서울 시간 내보낸 날.
 */
record ExportFileName(String korean, String ascii) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int NAME_MAX_LENGTH = 40;

    static ExportFileName all(LocalDate day) {
        return new ExportFileName("차곡-전체-" + DAY.format(day) + ".md", "chagok-all-" + DAY.format(day) + ".md");
    }

    static ExportFileName activity(long id, String name, LocalDate day) {
        return new ExportFileName("차곡-활동-" + tidy(name, id) + "-" + DAY.format(day) + ".md",
                "chagok-activity-" + id + "-" + DAY.format(day) + ".md");
    }

    static ExportFileName application(long id, String organization, LocalDate day) {
        return new ExportFileName("차곡-지원-" + tidy(organization, id) + "-" + DAY.format(day) + ".md",
                "chagok-application-" + id + "-" + DAY.format(day) + ".md");
    }

    /** Content-Disposition 값 — RFC 6266 의 ASCII filename 과 RFC 8187 의 UTF-8 filename*. */
    String contentDisposition() {
        String encoded = URLEncoder.encode(korean, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 파일 이름에 못 쓰는 글자(\ / : * ? " < > | · 제어 문자)는 빼고, 공백 여럿은 하나로, 앞뒤 공백 · 점은 뺀다. 40자가 넘으면 40자에서 자른다.
     * 다듬고 남은 게 없으면 ID.
     */
    static String tidy(String name, long id) {
        if (name == null) {
            return String.valueOf(id);
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s.]+|[\\s.]+$", "");
        if (cleaned.codePointCount(0, cleaned.length()) > NAME_MAX_LENGTH) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, NAME_MAX_LENGTH))
                    .replaceAll("[\\s.]+$", "");
        }
        return cleaned.isEmpty() ? String.valueOf(id) : cleaned;
    }
}

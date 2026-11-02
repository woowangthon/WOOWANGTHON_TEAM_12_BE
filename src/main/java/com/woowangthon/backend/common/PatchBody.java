package com.woowangthon.backend.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PATCH 본문 — 「보낸 필드만 바꾼다」(계약)를 위해 필드를 안 보낸 것과 null 을 보낸 것을 구분한다.
 * 필드가 없으면 Optional.empty(), 있으면 값(null 포함)을 감싼 Field.
 */
public final class PatchBody {

    private final Map<String, Object> raw;

    public PatchBody(Map<String, Object> raw) {
        if (raw == null) {
            throw ApiException.invalidRequest("요청 본문이 없습니다");
        }
        this.raw = raw;
    }

    public record Field<T>(T value) {
    }

    public Optional<Field<String>> string(String name) {
        return field(name, String.class, "문자열");
    }

    public Optional<Field<Boolean>> bool(String name) {
        return field(name, Boolean.class, "true · false");
    }

    public Optional<Field<List<?>>> list(String name) {
        return field(name, List.class, "배열").map(f -> new Field<>((List<?>) f.value()));
    }

    @SuppressWarnings("unchecked")
    public Optional<Field<PatchBody>> object(String name) {
        return field(name, Map.class, "객체").map(f -> new Field<>(f.value() == null ? null : new PatchBody(f.value())));
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<Field<T>> field(String name, Class<T> type, String expected) {
        if (!raw.containsKey(name)) {
            return Optional.empty();
        }
        Object value = raw.get(name);
        if (value != null && !type.isInstance(value)) {
            throw ApiException.invalidRequest(name + " 은(는) " + expected + "여야 합니다");
        }
        return Optional.of(new Field<>((T) value));
    }
}

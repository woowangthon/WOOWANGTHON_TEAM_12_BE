package com.woowangthon.backend.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** 도구 입력 읽기 — 빠졌거나 null 이면 비어 있고, 모양이 틀리면 INVALID_ARGUMENT. */
final class McpArgs {

    private McpArgs() {
    }

    static Optional<String> string(JsonNode args, String name) {
        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw McpToolException.invalidArgument(name + " 은(는) 문자열이어야 합니다");
        }
        return Optional.of(value.asString());
    }

    /** 필수 글 — 빠졌거나 비었거나 max 자를 넘으면 INVALID_ARGUMENT. */
    static String text(JsonNode args, String name, int max) {
        String value = string(args, name).orElseThrow(() -> McpToolException.invalidArgument(name + " 이(가) 없습니다"));
        if (value.isBlank()) {
            throw McpToolException.invalidArgument(name + " 이(가) 비었습니다");
        }
        if (value.length() > max) {
            throw McpToolException.invalidArgument(name + " 은(는) " + max + "자 이하여야 합니다");
        }
        return value;
    }

    static Optional<List<String>> strings(JsonNode args, String name) {
        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isArray()) {
            throw McpToolException.invalidArgument(name + " 은(는) 문자열 배열이어야 합니다");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                throw McpToolException.invalidArgument(name + " 은(는) 문자열 배열이어야 합니다");
            }
            result.add(item.asString());
        }
        return Optional.of(result);
    }

    /** 정수 — LLM 클라이언트가 "7" 처럼 숫자 문자열로 보내는 것도 받는다. */
    static Optional<Long> integer(JsonNode args, String name) {
        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return Optional.of(value.longValue());
        }
        if (value.isString() && value.asString().matches("-?\\d{1,18}")) {
            return Optional.of(Long.parseLong(value.asString()));
        }
        throw McpToolException.invalidArgument(name + " 은(는) 정수여야 합니다");
    }
}

package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ErrorResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 엔드포인트 — ETC docs/contracts/mcp-api.md 「POST /mcp/{token}」(SPEC F-11 · S-32 · 데모 7).
 * Streamable HTTP 를 상태 없이 쓴다: 요청 하나에 JSON-RPC 메시지 하나, 응답은 application/json 한 번(SSE · Mcp-Session-Id 없음).
 * 라이브러리 없이 다섯 메서드(initialize · notifications/initialized · ping · tools/list · tools/call)만 받는다.
 * /api 밖이라 로그인 세션 · 쿠키 · CORS 와 상관없고, 주소의 토큰으로만 사용자를 정한다. 없는 · 끊긴 토큰은 본문 없이 401.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** 최신이 앞. 클라이언트가 보낸 버전이 여기 있으면 그대로, 아니면 맨 앞 것으로 답한다. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final String SERVER_NAME = "woowang-experiences";

    // 계약 「읽는 규칙」 — 뜻은 바꾸지 않는다
    static final String INSTRUCTIONS = """
            이 서버는 사용자가 「내가 우왕이 될 상인가」에 쌓은 활동 · 경험 · 근거를 읽기 전용으로 돌려준다.
            - sourceStatus: SOURCE_CONFIRMED 는 원본 자료에서 확인된 경험, USER_ENTERED 는 사용자가 직접 넣거나 고친 것, NEEDS_REVIEW 는 근거를 원본에서 찾지 못해 확인이 필요한 것이다. NEEDS_REVIEW 를 확인된 사실처럼 말하지 않는다.
            - citations[].quote 는 원본 발췌문이다. 사실을 말할 때 근거로 이것과 sourceName 을 함께 든다.
            - supplements 는 사용자가 되묻기에 직접 답한 보완(「내가 입력」)이다. 원본 근거와 구분해 말한다.
            - actor 가 TEAM 이면 팀이 한 일이다 — 사용자 혼자 한 일처럼 바꿔 말하지 않는다. UNKNOWN 은 누가 했는지 모른다.
            - competencies 는 AI(competenciesBy=AI) 또는 사용자(USER)가 붙인 해석이다 — 원문 사실이 아니다.
            - 검색 결과가 없다는 것은 「그런 경험이 없다」가 아니라 「쌓인 자료에서 찾지 못했다」이다. 없는 경험 · 수치를 지어내지 않는다.""";

    private final McpConnectionService connections;
    private final Map<String, McpTool> tools;
    private final JsonMapper json;

    public McpController(McpConnectionService connections, List<McpTool> tools, JsonMapper json) {
        this.connections = connections;
        this.tools = tools.stream().collect(Collectors.toMap(McpTool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.json = json;
    }

    @PostMapping("/mcp/{token}")
    public ResponseEntity<Object> handle(@PathVariable String token, @RequestBody(required = false) String body) {
        Optional<McpConnectionService.Authenticated> owner = connections.authenticate(token);
        if (owner.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode message;
        try {
            message = json.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            return reply(HttpStatus.BAD_REQUEST, error(null, -32700, "JSON 을 읽을 수 없습니다"));
        }
        if (message == null || !message.isObject()) {
            // 배치(배열)는 받지 않는다 — MCP 2025-06-18 부터 없어졌다
            return reply(HttpStatus.BAD_REQUEST, error(null, -32600, "JSON-RPC 메시지 객체 하나를 보내야 합니다"));
        }

        JsonNode id = message.get("id");
        JsonNode method = message.get("method");
        // 알림(id 없음)과 클라이언트가 보낸 응답(method 없음)은 받기만 한다
        if (id == null || id.isNull() || method == null) {
            return ResponseEntity.accepted().build();
        }
        if (!method.isString()) {
            return reply(HttpStatus.OK, error(id, -32600, "method 가 문자열이 아닙니다"));
        }
        JsonNode params = message.path("params");

        return switch (method.asString()) {
            case "initialize" -> reply(HttpStatus.OK, result(id, initialize(params)));
            case "ping" -> reply(HttpStatus.OK, result(id, Map.of()));
            case "tools/list" -> reply(HttpStatus.OK,
                    result(id, Map.of("tools", tools.values().stream()
                            .filter(t -> visible(t, owner.get().scope())).map(McpTool::definition).toList())));
            case "tools/call" -> reply(HttpStatus.OK, callTool(id, owner.get(), params));
            default -> reply(HttpStatus.OK, error(id, -32601, "지원하지 않는 메서드: " + method.asString()));
        };
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params.path("protocolVersion").isString() ? params.path("protocolVersion").asString() : null;
        String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst();
        return Map.of(
                "protocolVersion", version,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", SERVER_NAME, "title", "내가 우왕이 될 상인가 — 내 경험", "version", "1.0.0"),
                "instructions", INSTRUCTIONS);
    }

    // 쓰기 도구는 CHAGOK 토큰에만 보인다(계약 「토큰 범위」)
    private static boolean visible(McpTool tool, String scope) {
        return !(tool instanceof McpWriteTool) || AccessToken.SCOPE_CHAGOK.equals(scope);
    }

    private Map<String, Object> callTool(JsonNode id, McpConnectionService.Authenticated owner, JsonNode params) {
        JsonNode name = params.path("name");
        McpTool tool = name.isString() ? tools.get(name.asString()) : null;
        if (tool == null || !visible(tool, owner.scope())) {
            return error(id, -32602, "없는 도구: " + (name.isString() ? name.asString() : name.toString()));
        }
        JsonNode arguments = params.path("arguments");
        if (arguments.isMissingNode() || arguments.isNull()) {
            arguments = json.createObjectNode();
        }
        try {
            if (!arguments.isObject()) {
                throw McpToolException.invalidArgument("arguments 는 객체여야 합니다");
            }
            Object value = tool.call(owner.userId(), arguments);
            return result(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", json.writeValueAsString(value))),
                    "structuredContent", value,
                    "isError", false));
        } catch (McpToolException e) {
            return result(id, toolError(e.code(), e.getMessage()));
        } catch (RuntimeException e) {
            // 정해진 결과로 대신 채우지 않는다(대회 규칙 2) — 외부 AI 에 실패를 그대로 알린다
            log.error("MCP 도구 {} 실패", tool.name(), e);
            return result(id, toolError("INTERNAL_ERROR", "서버 내부 오류"));
        }
    }

    private Map<String, Object> toolError(String code, String message) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", json.writeValueAsString(ErrorResponse.of(code, message)))),
                "isError", true);
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private static ResponseEntity<Object> reply(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}

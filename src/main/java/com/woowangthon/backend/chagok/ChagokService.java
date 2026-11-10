package com.woowangthon.backend.chagok;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AgentRequest;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.mcp.AccessToken;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.mcp.McpTool;
import com.woowangthon.backend.mcp.McpWriteTool;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.question.Draft;
import com.woowangthon.backend.question.DraftRepository;
import com.woowangthon.backend.question.Question;
import com.woowangthon.backend.question.Recommendation;
import com.woowangthon.backend.question.QuestionRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 차곡이 일(ETC docs/contracts/chagok-api.md · SPEC S-31). AI 스트림 읽기는 트랜잭션 밖(ChagokRunner)에서 하고,
 * 여기선 이벤트 하나를 단계 하나로 쌓는다. 상태 전환은 행을 잠그고 한다 — 스트림 · 확인 · 그만두기가 겹쳐도 한 번만.
 */
@Service
public class ChagokService {

    private static final Logger log = LoggerFactory.getLogger(ChagokService.class);

    private static final int MAX_LABEL_LENGTH = 60;
    private static final int MAX_MESSAGE_LENGTH = 300;
    // tool_result 짝 맞추기용 칸 — 계약 밖이라 응답에서 뺀다
    private static final String CALL_ID = "callId";
    // ai-api.md 「POST /agent」 error 코드
    private static final Set<String> AGENT_ERROR_CODES = Set.of("LLM_ERROR", "LLM_TIMEOUT", "TOO_MANY_STEPS",
            "MCP_UNAVAILABLE", "INTERNAL_ERROR");

    private final ChagokRunRepository runs;
    private final QuestionRepository questions;
    private final DraftRepository drafts;
    private final ExperienceRepository experiences;
    private final McpConnectionService connections;
    private final UsageLimiter limiter;
    private final JsonMapper json;
    private final Set<String> readTools;
    private final Map<String, McpWriteTool> writeTools;
    private final int maxTextLength;
    private final int maxToolCalls;

    public ChagokService(ChagokRunRepository runs, QuestionRepository questions, DraftRepository drafts,
            ExperienceRepository experiences, McpConnectionService connections,
            UsageLimiter limiter, JsonMapper json, List<McpTool> tools, List<McpWriteTool> writeTools,
            @Value("${app.source.max-text-length}") int maxTextLength,
            @Value("${app.chagok.max-tool-calls}") int maxToolCalls) {
        this.runs = runs;
        this.questions = questions;
        this.drafts = drafts;
        this.experiences = experiences;
        this.connections = connections;
        this.limiter = limiter;
        this.json = json;
        this.readTools = tools.stream().filter(t -> t.visibleTo(AccessToken.SCOPE_CHAGOK) && !(t instanceof McpWriteTool))
                .map(McpTool::name)
                .collect(Collectors.toSet());
        this.writeTools = writeTools.stream().collect(Collectors.toMap(McpTool::name, Function.identity()));
        this.maxTextLength = maxTextLength;
        this.maxToolCalls = maxToolCalls;
    }

    public record RunRequest(String text, Long questionId) {
    }

    public record Started(long runId) {
    }

    /** 뒤에서 돌릴 것 — 토큰이 들어 있어 응답 · 로그에 싣지 않는다. */
    public record Launch(long runId, long tokenId, AgentRequest request) {
    }

    public record Run(long runId, String text, Long questionId, ChagokStatus status, JsonNode steps, JsonNode result,
            ErrorResponse.Error error, OffsetDateTime createdAt) {
    }

    /** 확인 뒤 실행 결과 + 커밋 뒤 시작할 분석. */
    public record Executed(Run run, Runnable afterCommit) {
    }

    /** 확인 뒤 실행이 실패했다 — 롤백하고 일을 FAILED 로 둔다(응답은 200 + FAILED Run). */
    public static class ConfirmFailed extends RuntimeException {

        private final String code;

        ConfirmFailed(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    @Transactional
    public Launch start(RunRequest request) {
        long userId = CurrentUser.id();
        String text = checkText(request.text());
        AgentRequest.QuestionPart question = request.questionId() == null ? null : question(request.questionId(), userId);
        // 한도를 쓰기 전에 본다. 동시에 눌러 여기를 지나도 유니크 인덱스가 막는다
        if (runs.existsByUserIdAndStatus(userId, ChagokStatus.RUNNING)) {
            throw inProgress();
        }
        limiter.consume(userId);
        ChagokRun run;
        try {
            run = runs.saveAndFlush(new ChagokRun(userId, text, request.questionId()));
        } catch (DataIntegrityViolationException e) {
            throw inProgress();
        }
        McpConnectionService.ChagokToken token = connections.createChagok(userId);
        return new Launch(run.getId(), token.id(),
                new AgentRequest(text, new AgentRequest.Context(question), token.url(), maxToolCalls));
    }

    @Transactional(readOnly = true)
    public Run get(long runId) {
        return response(runs.findByIdAndUserId(runId, CurrentUser.id()).orElseThrow(() -> notFound(runId)));
    }

    /** RUNNING · WAITING_CONFIRM 이면 CANCELED, 이미 끝났으면 그대로(계약 「그만두기」). */
    @Transactional
    public Run cancel(long runId) {
        ChagokRun run = runs.findForUpdate(runId, CurrentUser.id()).orElseThrow(() -> notFound(runId));
        if (run.getStatus() == ChagokStatus.RUNNING || run.getStatus() == ChagokStatus.WAITING_CONFIRM) {
            run.cancel();
        }
        return response(run);
    }

    /**
     * 마지막 CONFIRM 단계를 그대로 실행한다. 쓰기와 DONE 이 행 잠금 안의 한 트랜잭션이라 두 번 눌러도 한 번만 실행된다.
     * 실행이 실패하면 ConfirmFailed — 쓴 것은 되돌아간다.
     */
    @Transactional
    public Executed execute(long runId) {
        ChagokRun run = runs.findForUpdate(runId, CurrentUser.id()).orElseThrow(() -> notFound(runId));
        if (run.getStatus() != ChagokStatus.WAITING_CONFIRM) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_WAITING",
                    "일 " + runId + " 은(는) 확인을 기다리지 않습니다(" + run.getStatus() + ")");
        }
        ArrayNode steps = steps(run);
        JsonNode confirm = steps.get(steps.size() - 1);
        String tool = confirm.path("tool").asString();
        McpWriteTool.Written written;
        try {
            written = writeTools.get(tool).execute(confirm.path("arguments"));
        } catch (ApiException e) {
            throw new ConfirmFailed(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("일 {} 의 쓰기 도구 {} 실패", runId, tool, e);
            throw new ConfirmFailed("INTERNAL_ERROR", "서버 내부 오류");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", tool);
        result.putAll(written.value());
        run.done(json.writeValueAsString(result));
        return new Executed(response(run), written.afterCommit());
    }

    /** 확인 뒤 실행 실패를 남긴다. 그새 다른 요청이 끝냈으면 그대로 둔다. */
    @Transactional
    public Run failConfirm(long runId, String code, String message) {
        ChagokRun run = runs.findForUpdate(runId, CurrentUser.id()).orElseThrow(() -> notFound(runId));
        if (run.getStatus() == ChagokStatus.WAITING_CONFIRM) {
            run.fail(code, message);
        }
        return response(run);
    }

    /** 일이 아직 스트림을 읽어도 되나 — 스트림이 열리기 전에 그만뒀을 수 있다. */
    @Transactional(readOnly = true)
    public boolean isRunning(long runId) {
        return runs.findById(runId).map(r -> r.getStatus() == ChagokStatus.RUNNING).orElse(false);
    }

    /** 뒤에서 난 실패 — RUNNING 일 때만(그만뒀으면 그대로 둔다). */
    @Transactional
    public void fail(long runId, String code, String message) {
        ChagokRun run = runs.lockById(runId).orElseThrow();
        if (run.getStatus() == ChagokStatus.RUNNING) {
            run.fail(code, message);
        }
    }

    /**
     * AI 이벤트 한 줄을 단계로 쌓는다. true 면 읽기를 멈춘다(끝 이벤트 · 그새 그만둠).
     * 계약 밖 이벤트면 AiCallException(INVALID_AGENT_OUTPUT) — 쌓지 않고 실패로 둔다.
     */
    @Transactional
    public boolean apply(long runId, String line) {
        ChagokRun run = runs.lockById(runId).orElseThrow();
        if (run.getStatus() != ChagokStatus.RUNNING) {
            return true;
        }
        JsonNode event = parse(line);
        ArrayNode steps = steps(run);
        String type = event.path("type").isString() ? event.path("type").asString() : "";
        boolean finished = false;
        switch (type) {
            case "tool_call" -> addToolCall(steps, event);
            case "tool_result" -> applyToolResult(steps, event);
            case "message" -> addMessage(steps, event);
            case "confirm" -> {
                addConfirm(steps, event);
                run.waitForConfirm();
                finished = true;
            }
            case "done" -> {
                run.done(null);
                finished = true;
            }
            case "error" -> {
                failByAgent(run, event);
                finished = true;
            }
            default -> throw invalid("모르는 이벤트: " + type);
        }
        run.changeSteps(json.writeValueAsString(steps));
        return finished;
    }

    private void addToolCall(ArrayNode steps, JsonNode event) {
        String id = requiredText(event, "id");
        String tool = requiredText(event, "tool");
        String label = requiredText(event, "label");
        if (label.length() > MAX_LABEL_LENGTH) {
            throw invalid("tool_call.label 이 " + MAX_LABEL_LENGTH + "자를 넘습니다");
        }
        if (!readTools.contains(tool)) {
            throw invalid("tool_call 은 읽기 도구만 부릅니다: " + tool);
        }
        if (toolCall(steps, id) != null) {
            throw invalid("tool_call.id 가 겹칩니다: " + id);
        }
        ObjectNode step = newStep(steps, "TOOL_CALL", label);
        step.put(CALL_ID, id);
        step.put("tool", tool);
        step.set("arguments", requiredObject(event, "arguments"));
        step.putNull("ok");
        step.putNull("summary");
        step.putNull("result");
        step.putNull("error");
    }

    private void applyToolResult(ArrayNode steps, JsonNode event) {
        String id = requiredText(event, "id");
        ObjectNode step = toolCall(steps, id);
        if (step == null || !step.path("ok").isNull()) {
            throw invalid("짝이 맞는 tool_call 이 없는 tool_result: " + id);
        }
        if (!event.path("ok").isBoolean()) {
            throw invalid("tool_result.ok 가 참 · 거짓이 아닙니다");
        }
        step.put("ok", event.path("ok").booleanValue());
        step.put("summary", requiredText(event, "summary"));
        copyOrNull(step, event, "result");
        copyOrNull(step, event, "error");
    }

    private static void copyOrNull(ObjectNode step, JsonNode event, String field) {
        if (event.path(field).isMissingNode()) {
            step.putNull(field);
        } else {
            step.set(field, event.path(field));
        }
    }

    private void addMessage(ArrayNode steps, JsonNode event) {
        String text = requiredText(event, "text");
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw invalid("message.text 가 " + MAX_MESSAGE_LENGTH + "자를 넘습니다");
        }
        newStep(steps, "MESSAGE", null).put("text", text);
    }

    private void addConfirm(ArrayNode steps, JsonNode event) {
        String tool = requiredText(event, "tool");
        if (!writeTools.containsKey(tool)) {
            throw invalid("confirm 의 도구가 쓰기 도구가 아닙니다: " + tool);
        }
        ObjectNode step = newStep(steps, "CONFIRM", requiredText(event, "label"));
        step.put("tool", tool);
        step.set("arguments", requiredObject(event, "arguments"));
    }

    private static void failByAgent(ChagokRun run, JsonNode event) {
        String code = requiredText(event, "code");
        if (!AGENT_ERROR_CODES.contains(code)) {
            throw invalid("모르는 error.code: " + code);
        }
        run.fail(code, requiredText(event, "message"));
    }

    private static ObjectNode newStep(ArrayNode steps, String type, String label) {
        ObjectNode step = steps.addObject();
        step.put("seq", steps.size());
        step.put("type", type);
        step.put("label", label);
        step.put("at", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Times.seoul(OffsetDateTime.now())));
        return step;
    }

    private static ObjectNode toolCall(ArrayNode steps, String id) {
        for (JsonNode step : steps) {
            if (id.equals(step.path(CALL_ID).asString(""))) {
                return (ObjectNode) step;
            }
        }
        return null;
    }

    private JsonNode parse(String line) {
        JsonNode event;
        try {
            event = json.readTree(line);
        } catch (JacksonException e) {
            throw invalid("JSON 이 아닌 줄");
        }
        if (event == null || !event.isObject()) {
            throw invalid("이벤트가 객체가 아닙니다");
        }
        return event;
    }

    private static String requiredText(JsonNode event, String field) {
        JsonNode value = event.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw invalid(event.path("type").asString("") + "." + field + " 이(가) 없습니다");
        }
        return value.asString();
    }

    private static JsonNode requiredObject(JsonNode event, String field) {
        JsonNode value = event.path(field);
        if (!value.isObject()) {
            throw invalid(event.path("type").asString("") + "." + field + " 이(가) 객체가 아닙니다");
        }
        return value;
    }

    private static AiCallException invalid(String detail) {
        return new AiCallException("INVALID_AGENT_OUTPUT", "AI 응답이 계약을 벗어났습니다 — " + detail);
    }

    private ArrayNode steps(ChagokRun run) {
        return (ArrayNode) json.readTree(run.getSteps());
    }

    private Run response(ChagokRun run) {
        ArrayNode steps = steps(run);
        steps.forEach(step -> ((ObjectNode) step).remove(CALL_ID));
        return new Run(run.getId(), run.getText(), run.getQuestionId(), run.getStatus(), steps,
                run.getResult() == null ? null : json.readTree(run.getResult()),
                run.getErrorCode() == null ? null : new ErrorResponse.Error(run.getErrorCode(), run.getErrorMessage()),
                Times.seoul(run.getCreatedAt()));
    }

    private AgentRequest.QuestionPart question(long questionId, long userId) {
        Question question = questions.findByIdAndUserId(questionId, userId)
                .orElseThrow(() -> ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다"));
        return new AgentRequest.QuestionPart(question.getId(), question.getText(), question.getOrganization(),
                experiencesOf(question, userId));
    }

    /**
     * 그 문항에 지금 걸린 경험 — 추천 순서대로, 그 뒤 추천엔 없고 초안에만 쓴 경험(S-90 재매칭 뒤 생긴다). 지워진 · 남의 경험은 뺀다.
     * 「지금 경험 말고 다른 것」 부탁에 차곡이가 되묻지 않게 한다(ETC#300). FE 에서 체크한 활동은 BE 가 모른다 — 초안 경험이 대신한다.
     */
    private List<AgentRequest.ExperiencePart> experiencesOf(Question question, long userId) {
        List<Long> inDraft = drafts.findByQuestionId(question.getId()).map(Draft::getExperienceIds).orElse(List.of());
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        question.getRecommendations().stream().map(Recommendation::getExperienceId).forEach(ids::add);
        Set<Long> recommended = Set.copyOf(ids);
        ids.addAll(inDraft);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Experience> found = experiences.findAllOwned(ids, userId).stream()
                .collect(Collectors.toMap(Experience::getId, Function.identity()));
        return ids.stream().filter(found::containsKey).map(found::get)
                .map(e -> new AgentRequest.ExperiencePart(e.getId(), e.getActivity().getName(), e.getContent(),
                        recommended.contains(e.getId()), inDraft.contains(e.getId())))
                .toList();
    }

    private String checkText(String text) {
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > maxTextLength) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG", "텍스트가 " + maxTextLength + "자를 넘습니다");
        }
        return text;
    }

    private static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "RUN_IN_PROGRESS", "이미 진행 중인 일이 있습니다");
    }

    private static ApiException notFound(long runId) {
        return ApiException.notFound("RUN_NOT_FOUND", "일 " + runId + " 이(가) 없습니다");
    }
}

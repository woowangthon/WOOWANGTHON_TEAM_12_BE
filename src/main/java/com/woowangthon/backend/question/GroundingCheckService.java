package com.woowangthon.backend.question;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.GroundCheckRequest;
import com.woowangthon.backend.ai.GroundCheckResponse;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.ErrorResponse;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.job.JobStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근거 점검 시작 → AI 점검 → 결과 저장 (question-api.md 「근거 점검」, ai-api.md 「POST /ground-check」). 문항마다 최신 하나.
 * AI 호출 자체는 트랜잭션 밖(GroundingRunner)에서 한다.
 */
@Service
public class GroundingCheckService {

    private final QuestionRepository questions;
    private final GroundingCheckRepository checks;
    private final ExperienceRepository experiences;
    private final UsageLimiter limiter;

    public GroundingCheckService(QuestionRepository questions, GroundingCheckRepository checks,
            ExperienceRepository experiences, UsageLimiter limiter) {
        this.questions = questions;
        this.checks = checks;
        this.experiences = experiences;
        this.limiter = limiter;
    }

    public record StartRequest(String text) {
    }

    public record EvidenceRef(EvidenceType type, Long id, Long experienceId, Long activityId, String text) {
    }

    public record Sentence(String text, GroundingStatus status, List<EvidenceRef> evidence, String note) {
    }

    public record Check(Long questionId, JobStatus status, ErrorResponse.Error error, String text,
            List<Sentence> sentences, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    // GET — 아직 점검하지 않았으면 null
    public record Result(Check groundingCheck) {
    }

    /**
     * 점검을 시작한다 — 그 문항의 예전 점검을 이것으로 바꾼다. 진행 중(409) · 한도(429)면 예전 것을 그대로 둔다. 문항 행을 잠가 두 번 눌러도
     * 하나만 시작한다.
     */
    @Transactional
    public Check start(long questionId, StartRequest request) {
        String text = checkText(request == null ? null : request.text());
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> notFound(questionId));
        GroundingCheck check = checks.findByQuestionId(questionId).orElse(null);
        if (check != null && check.getStatus() == JobStatus.ANALYZING) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUNDING_CHECK_IN_PROGRESS",
                    "문항 " + questionId + " 의 근거 점검이 아직 진행 중입니다");
        }
        limiter.consume(question.getUserId());
        if (check == null) {
            check = new GroundingCheck(questionId);
        }
        check.start(text);
        checks.saveAndFlush(check);
        return response(check, question.getUserId());
    }

    @Transactional(readOnly = true)
    public Result get(long questionId) {
        Question question = questions.findByIdAndUserId(questionId, CurrentUser.id())
                .orElseThrow(() -> notFound(questionId));
        return new Result(checks.findByQuestionId(questionId).map(c -> response(c, question.getUserId())).orElse(null));
    }

    /** AI 에 보낼 것 — 점검할 글과 그 사용자의 경험 전부를 지금 상태로. 근거는 원본에서 찾은 것만(「원문」으로 칠하지 않게). */
    @Transactional(readOnly = true)
    public GroundCheckRequest request(long questionId) {
        Question question = questions.findById(questionId).orElseThrow();
        GroundingCheck check = checks.findByQuestionId(questionId).orElseThrow();
        List<MatchRequest.ExperiencePart> parts = experiences.findAllOfUser(question.getUserId()).stream()
                .map(e -> new MatchRequest.ExperiencePart(e.getId(), e.getActivity().getName(),
                        e.getActivity().getPeriod(), e.getContent(), e.getCompetencies(), e.getActor(),
                        e.getSourceStatus(),
                        e.getCitations().stream().filter(c -> Boolean.TRUE.equals(c.getMatched()))
                                .map(c -> new MatchRequest.CitationPart(c.getId(), c.getQuote())).toList(),
                        e.getSupplements().stream()
                                .map(s -> new MatchRequest.SupplementPart(s.getId(), s.getQuestion(), s.getAnswer())).toList()))
                .toList();
        return new GroundCheckRequest(check.getText(), parts);
    }

    /**
     * AI 응답을 문장으로 저장한다. 계약(ai-api.md 「응답 규칙」)을 벗어난 칸이 하나라도 있으면 아무것도 남기지 않고 AiCallException(AI_FAILED)을
     * 던진다 — 고쳐서 쓰지 않는다. ID 는 보낸 요청(sent)에 있었는지로 확인한다.
     */
    @Transactional
    public void complete(long questionId, GroundCheckRequest sent, GroundCheckResponse result) {
        GroundingCheck check = checks.lockByQuestionId(questionId).orElseThrow();
        if (check.getStatus() != JobStatus.ANALYZING) {
            return; // 그새 재시작 정리로 실패가 됐다 — 덮지 않는다
        }
        Set<Long> citationIds = new HashSet<>();
        Set<Long> supplementIds = new HashSet<>();
        Set<Long> userEnteredIds = new HashSet<>();
        for (MatchRequest.ExperiencePart e : sent.experiences()) {
            e.citations().forEach(c -> citationIds.add(c.id()));
            e.supplements().forEach(s -> supplementIds.add(s.id()));
            if (e.sourceStatus() == SourceStatus.USER_ENTERED) {
                userEnteredIds.add(e.id());
            }
        }

        List<GroundCheckResponse.SentenceResult> found = result.sentences();
        if (found == null || found.isEmpty()) {
            throw AiCallException.invalidResponse("sentences 가 비었습니다");
        }
        List<GroundingSentence> sentences = new ArrayList<>();
        int cursor = 0;
        for (int position = 0; position < found.size(); position++) {
            GroundCheckResponse.SentenceResult s = found.get(position);
            if (s.text() == null || s.text().isBlank()) {
                throw AiCallException.invalidResponse("sentences[" + position + "].text 가 비었습니다");
            }
            // 문장은 보낸 글의 이어진 일부이고 글의 순서대로다 — AI 가 다시 쓴 문장을 저장하지 않는다
            int at = sent.text().indexOf(s.text(), cursor);
            if (at < 0) {
                throw AiCallException.invalidResponse("sentences[" + position + "].text 가 보낸 글에 순서대로 없습니다");
            }
            cursor = at + s.text().length();

            GroundingStatus status = groundingStatus(s.status());
            List<Evidence> evidence = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (MatchResponse.EvidenceResult e : orEmpty(s.evidence())) {
                EvidenceType type = evidenceType(e.type());
                Set<Long> allowed = switch (type) {
                    case CITATION -> citationIds;
                    case SUPPLEMENT -> supplementIds;
                    case EXPERIENCE -> userEnteredIds;
                };
                if (e.id() == null || !allowed.contains(e.id())) {
                    throw AiCallException.invalidResponse("evidence 의 " + type + " " + e.id() + " 은(는) 보낸 것이 아닙니다");
                }
                if (!seen.add(type + ":" + e.id())) {
                    throw AiCallException.invalidResponse("evidence 에 같은 것이 두 번 있습니다: " + type + " " + e.id());
                }
                evidence.add(new Evidence(type, e.id()));
            }
            checkEvidence(position, status, evidence);
            String note = s.note();
            if (status == GroundingStatus.UNSUPPORTED ? note == null || note.isBlank() : note != null) {
                throw AiCallException.invalidResponse(
                        "sentences[" + position + "].note 는 UNSUPPORTED 면 한 줄, 아니면 null 이어야 합니다(" + status + ")");
            }
            sentences.add(new GroundingSentence(check, position, s.text(), status, evidence, note));
        }
        check.complete(sentences);
    }

    @Transactional
    public void fail(long questionId, String code, String message) {
        checks.lockByQuestionId(questionId).orElseThrow().fail(code, message);
    }

    /**
     * 문장의 근거에 경험 · 활동 · 글을 지금 상태로 붙인다. 그새 지워진 근거 · 보완 · 경험은 뺀다 — 판정은 점검했을 때 그대로다.
     */
    private Check response(GroundingCheck c, long userId) {
        List<Sentence> sentences = List.of();
        if (!c.getSentences().isEmpty()) {
            Map<String, EvidenceRef> current = currentEvidence(userId);
            sentences = c.getSentences().stream()
                    .map(s -> new Sentence(s.getText(), s.getStatus(),
                            s.getEvidence().stream().map(v -> current.get(v.getType() + ":" + v.getRefId()))
                                    .filter(Objects::nonNull).toList(),
                            s.getNote()))
                    .toList();
        }
        ErrorResponse.Error error = c.getErrorCode() == null ? null
                : new ErrorResponse.Error(c.getErrorCode(), c.getErrorMessage());
        return new Check(c.getQuestionId(), c.getStatus(), error, c.getText(), sentences,
                Times.seoul(c.getCreatedAt()), c.getUpdatedAt() == null ? null : Times.seoul(c.getUpdatedAt()));
    }

    private Map<String, EvidenceRef> currentEvidence(long userId) {
        Map<String, EvidenceRef> current = new HashMap<>();
        for (Experience e : experiences.findAllOfUser(userId)) {
            Long activityId = e.getActivity().getId();
            current.put(EvidenceType.EXPERIENCE + ":" + e.getId(),
                    new EvidenceRef(EvidenceType.EXPERIENCE, e.getId(), e.getId(), activityId, e.getContent()));
            for (Citation c : e.getCitations()) {
                current.put(EvidenceType.CITATION + ":" + c.getId(),
                        new EvidenceRef(EvidenceType.CITATION, c.getId(), e.getId(), activityId, c.getQuote()));
            }
            for (Supplement s : e.getSupplements()) {
                current.put(EvidenceType.SUPPLEMENT + ":" + s.getId(),
                        new EvidenceRef(EvidenceType.SUPPLEMENT, s.getId(), e.getId(), activityId, s.getAnswer()));
            }
        }
        return current;
    }

    // SOURCE → CITATION 1개 이상, USER_INPUT → SUPPLEMENT · EXPERIENCE 만 1개 이상, UNSUPPORTED · OPINION → []
    private static void checkEvidence(int position, GroundingStatus status,
            List<Evidence> evidence) {
        boolean hasCitation = evidence.stream().anyMatch(e -> e.getType() == EvidenceType.CITATION);
        boolean ok = switch (status) {
            case SOURCE -> hasCitation;
            case USER_INPUT -> !evidence.isEmpty() && !hasCitation;
            case UNSUPPORTED, OPINION -> evidence.isEmpty();
        };
        if (!ok) {
            throw AiCallException.invalidResponse("sentences[" + position + "].evidence 가 " + status + " 와 맞지 않습니다");
        }
    }

    private static GroundingStatus groundingStatus(String value) {
        return Stream.of(GroundingStatus.values()).filter(s -> s.name().equals(value)).findFirst()
                .orElseThrow(() -> AiCallException.invalidResponse(
                        "sentences[].status 는 SOURCE · USER_INPUT · UNSUPPORTED · OPINION 중 하나여야 합니다: " + value));
    }

    private static EvidenceType evidenceType(String value) {
        return Stream.of(EvidenceType.values()).filter(t -> t.name().equals(value)).findFirst()
                .orElseThrow(() -> AiCallException.invalidResponse(
                        "evidence[].type 은 CITATION · SUPPLEMENT · EXPERIENCE 중 하나여야 합니다: " + value));
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    // 한도는 문항 넣기와 같은 셈(String.length())
    private static String checkText(String text) {
        if (text == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "text 가 비었습니다");
        }
        if (text.length() > QuestionService.MAX_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "점검할 답이 " + QuestionService.MAX_TEXT_LENGTH + "자를 넘습니다");
        }
        return text;
    }

    private static ApiException notFound(long questionId) {
        return ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다");
    }
}

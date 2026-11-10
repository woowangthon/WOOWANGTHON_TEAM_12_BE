package com.woowangthon.backend.question;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.Times;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 쓰는 중인 답 임시 글(question-api.md 「임시 글」 · S-72). AI 를 부르지 않고 분석 횟수를 쓰지 않는다.
 * 확정(AnswerService.confirm)하면 지운다. 문항 행을 잠가 확정과 겹치지 않게 한다.
 */
@Service
public class AnswerDraftService {

    static final int MAX_STATE_LENGTH = 50_000;

    private final QuestionRepository questions;
    private final AnswerDraftRepository drafts;
    private final JsonMapper json;

    public AnswerDraftService(QuestionRepository questions, AnswerDraftRepository drafts, JsonMapper json) {
        this.questions = questions;
        this.drafts = drafts;
        this.json = json;
    }

    public record SaveRequest(String text, JsonNode state) {
    }

    public record AnswerDraftView(String text, JsonNode state, OffsetDateTime updatedAt) {
    }

    // PUT 의 응답 { "answerDraft": AnswerDraft }
    public record Result(AnswerDraftView answerDraft) {
    }

    /** 그 문항의 임시 글을 통째로 바꾼다 — state 를 빼면 null 로. */
    @Transactional
    public Result save(long questionId, SaveRequest request) {
        if (request == null || request.text() == null) {
            throw ApiException.invalidRequest("text 가 없습니다");
        }
        String text = request.text();
        JsonNode state = request.state();
        if (state != null && state.isNull()) {
            state = null;
        }
        if (state != null && !state.isObject()) {
            throw ApiException.invalidRequest("state 는 JSON 객체여야 합니다");
        }
        String stateText = state == null ? null : json.writeValueAsString(state);
        if (text.length() > QuestionService.MAX_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "임시 글이 " + QuestionService.MAX_TEXT_LENGTH + "자를 넘습니다");
        }
        if (stateText != null && stateText.length() > MAX_STATE_LENGTH) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TEXT_TOO_LONG",
                    "state 가 " + MAX_STATE_LENGTH + "자를 넘습니다");
        }
        questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다"));
        AnswerDraft draft = drafts.findByQuestionId(questionId).orElseGet(() -> new AnswerDraft(questionId));
        draft.write(text, stateText);
        drafts.saveAndFlush(draft);
        return new Result(view(draft));
    }

    /** 문항 응답에 싣는 임시 글 — 없으면 null. 문항을 읽는 트랜잭션 안에서 부른다. */
    public AnswerDraftView find(Question question) {
        return drafts.findByQuestionId(question.getId()).map(this::view).orElse(null);
    }

    /** 확정하면 지운다 — 문항 행을 잠근 트랜잭션 안에서 부른다. */
    void discard(long questionId) {
        drafts.deleteByQuestionId(questionId);
    }

    private AnswerDraftView view(AnswerDraft d) {
        JsonNode state = d.getState() == null ? null : json.readTree(d.getState());
        return new AnswerDraftView(d.getText(), state, Times.seoul(d.getUpdatedAt()));
    }
}

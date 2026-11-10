package com.woowangthon.backend.question;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.Times;
import com.woowangthon.backend.revision.Restored;
import com.woowangthon.backend.revision.Revision;
import com.woowangthon.backend.revision.RevisionErrors;
import com.woowangthon.backend.revision.RevisionKind;
import com.woowangthon.backend.revision.RevisionOrigin;
import com.woowangthon.backend.revision.RevisionRecorder;
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
    private final AnswerRepository answers;
    private final RevisionRecorder recorder;

    public AnswerDraftService(QuestionRepository questions, AnswerDraftRepository drafts, JsonMapper json,
            AnswerRepository answers, RevisionRecorder recorder) {
        this.questions = questions;
        this.drafts = drafts;
        this.json = json;
        this.answers = answers;
        this.recorder = recorder;
    }

    // fromDraft — 초안(S-27)을 답칸에 넣은 저장이면 true. 그때만 수정 이력에 남는다(S-80). 자동 저장은 빼거나 false
    public record SaveRequest(String text, JsonNode state, Boolean fromDraft) {
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
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("QUESTION_NOT_FOUND", "문항 " + questionId + " 이(가) 없습니다"));
        AnswerDraft draft = drafts.findByQuestionId(questionId).orElse(null);
        // 수정 이력(S-80) — 답칸에 보이던 글을 쓰기 전에 읽어 둔다
        String shown = Boolean.TRUE.equals(request.fromDraft()) ? shownText(questionId, draft) : null;
        if (draft == null) {
            draft = new AnswerDraft(questionId);
        }
        draft.write(text, stateText);
        drafts.saveAndFlush(draft);
        if (shown != null) {
            recorder.answer(question, shown, text, RevisionKind.DRAFT_OVERWRITE, RevisionOrigin.USER, null);
        }
        return new Result(view(draft));
    }

    /**
     * 수정 이력의 되돌리기(S-80) — 확정 답을 바꾸지 않고 그 글을 임시 글로 넣는다. 「다 썼어요」를 눌러야 확정이다.
     * 답칸에 보이는 글(임시 글, 없으면 확정 답, 둘 다 없으면 "")이 이미 그 글이면 409, 지운 문항이면 409(복구는 범위 밖).
     */
    @Transactional
    public Restored<AnswerDraftView> restore(long questionId, String text, long revisionId) {
        Question question = questions.findForUpdate(questionId, CurrentUser.id())
                .orElseThrow(RevisionErrors::targetDeleted);
        AnswerDraft draft = drafts.findByQuestionId(questionId).orElse(null);
        String shown = shownText(questionId, draft);
        if (shown.equals(text)) {
            throw RevisionErrors.nothingToRestore();
        }
        if (draft == null) {
            draft = new AnswerDraft(questionId);
        }
        draft.write(text, null);
        drafts.saveAndFlush(draft);
        Revision revision = recorder.answer(question, shown, text, RevisionKind.RESTORE, RevisionOrigin.USER,
                revisionId);
        return new Restored<>(view(draft), revision);
    }

    // 지금 답칸에 보이는 글 — 임시 글이 있으면 그것, 없으면 확정 답, 둘 다 없으면 ""
    private String shownText(long questionId, AnswerDraft draft) {
        if (draft != null) {
            return draft.getText();
        }
        return answers.findByQuestionId(questionId).map(Answer::getText).orElse("");
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

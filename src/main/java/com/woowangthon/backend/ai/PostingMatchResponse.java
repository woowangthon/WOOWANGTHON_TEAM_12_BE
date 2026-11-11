package com.woowangthon.backend.ai;

import java.util.List;

/** ai-api.md 「POST /posting-match」 응답. 값 목록 · ID 는 BE 가 다시 확인한다(PostingMatchService). */
public record PostingMatchResponse(List<LineResult> lines, List<ClarificationResult> clarifications, String model) {

    public record LineResult(Long id, String status, List<Long> experienceIds) {
    }

    public record ClarificationResult(Long lineId, Long experienceId, String question) {
    }
}

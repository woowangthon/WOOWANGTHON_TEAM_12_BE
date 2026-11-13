package com.woowangthon.backend.mcp;

/**
 * 외부 쓰기 도구(READ_WRITE) 결과의 notice — 외부 AI 가 사용자에게 그대로 전할 고정 문구(계약 「외부 쓰기 도구」, ETC#316).
 * 웹에서 「맞아요」를 눌러야 반영된다는 것을 학생이 모르는 일을 막는다. 차곡이 실행 결과에는 싣지 않는다.
 */
final class McpNotice {

    static final String ADD_SOURCE = "분석 중이에요 — 끝나면 search_experiences 로 볼 수 있어요. 웹 ‘변경 기록’에서 되돌릴 수 있어요.";
    static final String UPDATE_EXPERIENCE = "고친 글은 ‘확인 필요’ 상태예요. 웹에서 ‘맞아요’를 누르면 확정되고, 되돌릴 수도 있어요.";
    static final String CREATE_APPLICATION = "추천을 만드는 중이에요 — 끝나면 get_question 으로 볼 수 있어요. 웹 ‘변경 기록’에서 되돌릴 수 있어요.";
    static final String CREATE_APPLICATION_POSTING_ONLY = "공고 요건 줄로 지원을 만들었어요 — 공고 기준 경험 찾기는 웹 지원 화면에서 볼 수 있어요. 웹 ‘변경 기록’에서 되돌릴 수 있어요.";
    static final String ANSWER_CLARIFICATION = "이 답은 웹에서 ‘맞아요’를 누르기 전까지 추천 · 초안의 근거로 쓰이지 않아요.";

    private McpNotice() {
    }
}

-- 그때의 나(SPEC S-34, ETC docs/contracts/experience-api.md 「ActivitySummary」 note). BE#46
-- 활동의 계기 · 그때 든 생각 한 줄. 사용자만 쓰고, 경험이 아니라 추천 · 초안 근거로 쓰지 않는다.
-- 길이 · 줄바꿈 규칙은 PATCH 에서 확인한다. 기존 활동은 계기가 없다(데이터 옮기기 없음).

alter table activity
    add column note text;

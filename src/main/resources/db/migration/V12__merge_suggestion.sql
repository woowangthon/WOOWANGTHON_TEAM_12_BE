-- 같은 활동 제안(SPEC S-44, ETC docs/contracts/experience-api.md 「ActivitySummary」 mergeSuggestion). BE#52
-- 분석 작업이 /extract 응답의 sameAsActivityId 로 새 활동에만 붙인다. 따로 두기 · 합치기 때 null 이 된다.
-- 가리키는 활동이 지워지면 null — 다른 활동으로 옮겨 가리키지 않는다(BE 도 합치기 때 먼저 null 로 둔다).
-- 기존 활동은 제안이 없다(데이터 옮기기 없음).

alter table activity
    add column merge_suggestion_activity_id bigint references activity (id) on delete set null;

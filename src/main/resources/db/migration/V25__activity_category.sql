-- 활동 분류(SPEC S-57 · 미결정 5, ETC docs/contracts/experience-api.md 「ActivitySummary」 category · categoryBy). BE#95
-- 활동에 하나 · 닫힌 목록 셋. 분석이 붙이면 AI, 사용자가 고르면 USER. 비어 있으면 미분류(둘 다 null).
-- 기존 활동은 다시 분석하지 않아 null 로 남는다. 정리용이라 매칭 · 초안 · 근거 점검 · MCP 에 싣지 않는다.

alter table activity
    add column category    varchar(10) check (category in ('PROJECT', 'STUDY', 'CLUB')),
    add column category_by varchar(10) check (category_by in ('AI', 'USER')),
    add constraint activity_category_pair_check check ((category is null) = (category_by is null));

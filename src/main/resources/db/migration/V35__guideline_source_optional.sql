-- 작성 지침의 출처를 선택으로(SPEC S-49, ETC docs/contracts/guideline-api.md · question-api.md). BE#138
-- 이미 있는 값은 그대로 두고 not null 만 푼다. 출처가 없으면 null — 빈 문자열과 섞지 않는다.
alter table guideline alter column source drop not null;
alter table draft_guideline alter column source drop not null;

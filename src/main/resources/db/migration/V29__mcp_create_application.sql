-- 외부 AI 지원 만들기(SPEC S-55, ETC docs/contracts/mcp-api.md 「변경 기록」, ADR-011). BE#93
-- 지원도 FK 없이 ID 만 둔다 — 되돌려 지워져도 기록은 남는다. organization · question_count 는 만들 때 값이다.
alter table mcp_change drop constraint mcp_change_type_check;
alter table mcp_change add constraint mcp_change_type_check
    check (type in ('ADD_SOURCE', 'UPDATE_EXPERIENCE', 'CREATE_APPLICATION'));

alter table mcp_change add column application_id bigint;
alter table mcp_change add column organization text;
alter table mcp_change add column question_count integer;

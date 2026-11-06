-- 외부 AI 되묻기 답(SPEC S-56, ETC docs/contracts/mcp-api.md 「answer_clarification」 · 「변경 기록」, ADR-011). BE#94
-- 보완도 FK 없이 ID 만 둔다 — 되돌려 지워져도 기록은 남는다. question · answer 는 전한 때 값이다.
alter table mcp_change drop constraint mcp_change_type_check;
alter table mcp_change add constraint mcp_change_type_check
    check (type in ('ADD_SOURCE', 'UPDATE_EXPERIENCE', 'CREATE_APPLICATION', 'ANSWER_CLARIFICATION'));

alter table mcp_change add column supplement_id bigint;
alter table mcp_change add column question text;
alter table mcp_change add column answer text;

-- 외부 AI 가 전해 사용자가 아직 정하지 않은 보완만 찬다 — Supplement.mcpChange. 맞아요 · 웹에서 답을 고치면 비운다. 찬 동안은 근거가 아니다
alter table supplement add column mcp_change_id bigint;
alter table supplement add column mcp_client text;

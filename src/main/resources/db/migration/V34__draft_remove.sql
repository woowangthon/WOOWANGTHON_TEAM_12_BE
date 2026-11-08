-- 초안 문장 「빼기」(SPEC S-79, ETC docs/contracts/question-api.md). BE#135
-- 사용자가 카드에서 뺀 문장 — 응답엔 그대로 오고 FE 가 답칸 글에서 뺀다. 새 문장 · 옛 문장은 false
alter table draft_sentence add column removed boolean not null default false;

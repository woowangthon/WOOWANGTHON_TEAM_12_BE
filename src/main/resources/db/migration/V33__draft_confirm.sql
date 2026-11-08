-- 초안 문장 「맞아요」 · 주체를 정한 시각(SPEC S-77 · S-79, ETC docs/contracts/question-api.md). BE#131

-- 사용자가 카드에서 「맞아요」를 눌렀나. 새 문장 · 옛 문장은 false
alter table draft_sentence add column confirmed boolean not null default false;

-- 수행 주체가 UNKNOWN 에서 처음 정해진 시각 — 그 뒤 다시 만들기를 세지 않는 데 쓴다. 다시 UNKNOWN 이 됐다 정해도 덮지 않는다
alter table experience add column actor_decided_at timestamptz;

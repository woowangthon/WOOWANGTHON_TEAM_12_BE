-- 초안 문장 종류(SPEC S-27 · ETC#140). BE#72
-- 옛 초안은 당시 모든 문장이 사실 근거를 요구했으므로 FACT 로 채운다 — 근거 개수로 추측하지 않는다.
-- 새 문장은 AI 가 준 값을 넣으므로 기본값은 채운 뒤 지운다.
alter table draft_sentence add column kind varchar(20) not null default 'FACT' check (kind in ('FACT', 'OPINION'));
alter table draft_sentence alter column kind drop default;

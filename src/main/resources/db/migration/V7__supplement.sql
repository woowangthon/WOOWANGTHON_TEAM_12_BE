-- 보완 — 되묻기에 사용자가 한 답. 새 경험이 아니라 그 경험에 딸린 질문 · 답 한 쌍(SPEC R-03 · 미결정 10, ETC docs/contracts/experience-api.md 「Supplement」). BE#23
-- 경험의 content · citations · source_status 는 건드리지 않는다. 보완은 늘 「내가 입력」이라 출처 칸이 없다.
-- 경험을 지우면 보완도 지운다. 경험을 옮기거나 활동을 합쳐도 experience_id 가 그대로라 따라간다.
-- 되묻기 하나에 답 하나(clarification_id unique — 두 번째 답은 409). 보완을 지우면(S-24) 되묻기는 다시 답할 수 있다.

create table supplement (
    id               bigserial primary key,
    experience_id    bigint      not null references experience (id) on delete cascade,
    clarification_id bigint      unique references clarification (id) on delete set null,
    question_id      bigint      references question (id) on delete set null,
    question         text        not null,
    answer           text        not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

create index supplement_experience_id_idx on supplement (experience_id);

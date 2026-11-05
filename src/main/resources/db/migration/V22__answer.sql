-- 확정 답변 · 새 경험 후보(SPEC S-48, ETC docs/contracts/question-api.md 「확정 답변」). BE#81
-- 문항마다 최신 하나 — 다시 확정하면 같은 행을 다시 쓴다. 문항이 지워지면 같이 지운다.
-- new_facts_status 가 null 이면 후보 찾기를 아직 시작하지 않았다(계약 newFacts: null).

create table answer (
    id                     bigserial primary key,
    question_id            bigint      not null unique references question (id) on delete cascade,
    text                   text        not null,
    confirmed_at           timestamptz not null,
    new_facts_status       varchar(20) check (new_facts_status in ('ANALYZING', 'COMPLETED', 'FAILED')),
    error_code             varchar(30),
    error_message          text,
    new_facts_created_at   timestamptz,
    new_facts_updated_at   timestamptz
);

create index answer_new_facts_status_idx on answer (new_facts_status);

-- 그 문항에서 「저장 안 함」 한 문장 — 다시 확정해도 남아 다음 찾기에서 뺀다
create table answer_dismissed (
    answer_id bigint not null references answer (id) on delete cascade,
    sentence  text   not null
);

create index answer_dismissed_answer_id_idx on answer_dismissed (answer_id);

-- 지금 찾기의 후보 — 글의 순서대로. 다시 확정 · 다시 찾기면 지운다(만든 경험은 남는다).
-- 활동 · 경험이 지워지면 null 로(응답에서 activityId · experienceId 가 null)
create table new_fact_candidate (
    id            bigserial primary key,
    answer_id     bigint      not null references answer (id) on delete cascade,
    position      integer     not null,
    sentence      text        not null,
    content       text        not null,
    actor         varchar(20) not null check (actor in ('ME', 'TEAM', 'UNKNOWN')),
    activity_id   bigint      references activity (id) on delete set null,
    status        varchar(20) not null check (status in ('PENDING', 'SAVED', 'DISMISSED')),
    experience_id bigint      references experience (id) on delete set null
);

create index new_fact_candidate_answer_id_idx on new_fact_candidate (answer_id, position);

-- 경험이 온 곳(experienceOrigin) — 확정 답의 후보를 저장한 경험만 그 문항을 가리킨다. 그 밖은 null
alter table experience add column origin_question_id bigint references question (id) on delete set null;

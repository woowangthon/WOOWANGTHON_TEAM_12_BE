-- 답변 초안(SPEC S-27, ETC docs/contracts/question-api.md 「답변 초안」). BE#45
-- 문항마다 최신 하나 — 다시 만들면 같은 행을 다시 쓴다. 문항이 지워지면 같이 지운다.
-- 고른 경험 · 문장을 받치는 근거 · 보완 · 경험은 그새 지워질 수 있어 외래 키를 걸지 않는다(조회할 때 뺀다).

create table draft (
    id            bigserial primary key,
    question_id   bigint      not null unique references question (id) on delete cascade,
    status        varchar(20) not null check (status in ('ANALYZING', 'COMPLETED', 'FAILED')),
    error_code    varchar(30),
    error_message text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index draft_status_idx on draft (status);

-- 사용자가 고른 경험 — 보낸 순서대로
create table draft_experience (
    draft_id      bigint  not null references draft (id) on delete cascade,
    position      integer not null,
    experience_id bigint  not null,
    primary key (draft_id, position)
);

-- 받칠 게 없어 쓰지 않은 것(AI 해석)
create table draft_omitted (
    draft_id bigint  not null references draft (id) on delete cascade,
    position integer not null,
    text     text    not null,
    primary key (draft_id, position)
);

-- 문장 — 글의 순서대로
create table draft_sentence (
    id       bigserial primary key,
    draft_id bigint  not null references draft (id) on delete cascade,
    position integer not null,
    text     text    not null
);

create index draft_sentence_draft_id_idx on draft_sentence (draft_id, position);

-- 문장을 받치는 것 — 만들었을 때 그대로
create table draft_sentence_evidence (
    sentence_id bigint      not null references draft_sentence (id) on delete cascade,
    position    integer     not null,
    type        varchar(20) not null check (type in ('CITATION', 'SUPPLEMENT', 'EXPERIENCE')),
    ref_id      bigint      not null,
    primary key (sentence_id, position)
);

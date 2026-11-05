-- 근거 점검(SPEC S-45, ETC docs/contracts/question-api.md 「근거 점검」). BE#54
-- 문항마다 최신 하나 — 다시 점검하면 같은 행을 다시 쓴다. 문항이 지워지면 같이 지운다.
-- 문장을 받치는 근거 · 보완 · 경험은 그새 지워질 수 있어 외래 키를 걸지 않는다(조회할 때 뺀다).

create table grounding_check (
    id            bigserial primary key,
    question_id   bigint      not null unique references question (id) on delete cascade,
    text          text        not null,
    status        varchar(20) not null check (status in ('ANALYZING', 'COMPLETED', 'FAILED')),
    error_code    varchar(30),
    error_message text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index grounding_check_status_idx on grounding_check (status);

-- 문장 — 글의 순서대로. note 는 AI 해석이거나 AI 서버 코드가 쓴 문구(숫자 대조)
create table grounding_sentence (
    id       bigserial primary key,
    check_id bigint      not null references grounding_check (id) on delete cascade,
    position integer     not null,
    text     text        not null,
    status   varchar(20) not null check (status in ('SOURCE', 'USER_INPUT', 'UNSUPPORTED', 'OPINION')),
    note     text
);

create index grounding_sentence_check_id_idx on grounding_sentence (check_id, position);

-- 문장을 받치는 것 — 점검했을 때 그대로
create table grounding_sentence_evidence (
    sentence_id bigint      not null references grounding_sentence (id) on delete cascade,
    position    integer     not null,
    type        varchar(20) not null check (type in ('CITATION', 'SUPPLEMENT', 'EXPERIENCE')),
    ref_id      bigint      not null,
    primary key (sentence_id, position)
);

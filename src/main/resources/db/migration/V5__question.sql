-- 지원 문항 · 추천 결과(SPEC R-03 · 미결정 11 「저장한다」, ETC docs/contracts/question-api.md). BE#22
-- 문항 하나 = 추천 작업 하나 — 상태 · 실패 이유는 문항에 붙는다. 다시 시도는 같은 문항을 다시 돌린다.
-- 추천 · 요구사항 · 되묻기가 가리키는 경험은 그새 지워질 수 있어 experience 에 외래 키를 걸지 않는다(조회할 때 뺀다).

create table question (
    id            bigserial primary key,
    user_id       bigint      not null references app_user (id),
    organization  text,
    text          text        not null,
    status        varchar(20) not null check (status in ('ANALYZING', 'COMPLETED', 'FAILED')),
    error_code    varchar(30),
    error_message text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index question_user_id_idx on question (user_id, created_at desc);
create index question_status_idx on question (status);

-- 요구사항 — AI 가 돌려준 순서대로
create table question_requirement (
    question_id    bigint      not null references question (id) on delete cascade,
    position       integer     not null,
    text           text        not null,
    status         varchar(20) not null check (status in ('SUPPORTED', 'PARTIAL', 'NOT_FOUND')),
    experience_ids bigint[]    not null default '{}',
    primary key (question_id, position)
);

-- 추천 — 더 잘 맞는 것부터(AI 가 돌려준 순서). reason · emphasis · gaps 는 AI 해석이다
create table question_recommendation (
    id            bigserial primary key,
    question_id   bigint  not null references question (id) on delete cascade,
    position      integer not null,
    experience_id bigint  not null,
    reason        text    not null,
    emphasis      text    not null,
    gaps          text[]  not null default '{}'
);

create index question_recommendation_question_id_idx on question_recommendation (question_id, position);

-- 추천을 받치는 근거 · 보완 — 추천했을 때 그대로
create table question_recommendation_evidence (
    recommendation_id bigint      not null references question_recommendation (id) on delete cascade,
    position          integer     not null,
    type              varchar(20) not null check (type in ('CITATION', 'SUPPLEMENT')),
    ref_id            bigint      not null,
    primary key (recommendation_id, position)
);

-- 되묻기 — 답은 그 경험의 보완이 된다(BE#23)
create table clarification (
    id            bigserial primary key,
    question_id   bigint  not null references question (id) on delete cascade,
    position      integer not null,
    experience_id bigint  not null,
    question      text    not null
);

create index clarification_question_id_idx on clarification (question_id, position);

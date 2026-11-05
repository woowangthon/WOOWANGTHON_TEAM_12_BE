-- 차곡이 일(SPEC R-04 · F-10 · S-31, ETC docs/contracts/chagok-api.md · mcp-api.md 「토큰 범위」). BE#66
-- access_token 에 일마다 만드는 CHAGOK 토큰이 들어온다 — 만료 시각이 있고, 연결 화면 · 사용자당 하나 규칙에 안 섞인다.

alter table access_token drop constraint access_token_scope_check;
alter table access_token add constraint access_token_scope_check check (scope in ('READ', 'CHAGOK'));
alter table access_token add column expires_at timestamptz;

-- 단계는 Run 응답에 그대로 싣는 JSON 이라 칸으로 나누지 않는다. 일은 지우지 않는다(계약: 적어도 하루 남는다)
create table chagok_run (
    id            bigserial primary key,
    user_id       bigint      not null references app_user (id),
    text          text        not null,
    question_id   bigint,
    status        varchar(20) not null check (status in ('RUNNING', 'WAITING_CONFIRM', 'DONE', 'FAILED', 'CANCELED')),
    steps         text        not null,
    result        text,
    error_code    text,
    error_message text,
    created_at    timestamptz not null default now()
);

-- 한 사용자가 동시에 돌리는 일은 하나 — 동시에 눌러도 DB 가 막는다(409 RUN_IN_PROGRESS)
create unique index chagok_run_one_running_idx on chagok_run (user_id) where status = 'RUNNING';

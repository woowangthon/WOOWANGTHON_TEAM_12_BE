-- 분석 작업 — 원본 하나를 AI 로 추출하는 일의 상태(ETC docs/contracts/experience-api.md 「작업 상태」)
-- 다시 시도는 같은 작업을 다시 돌린다(새 행을 만들지 않는다).

create table analysis_job (
    id            bigserial primary key,
    user_id       bigint      not null references app_user (id),
    source_id     bigint      not null references source (id),
    status        varchar(20) not null check (status in ('ANALYZING', 'COMPLETED', 'FAILED')),
    error_code    varchar(30),
    error_message text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index analysis_job_user_id_idx on analysis_job (user_id);
create index analysis_job_status_idx on analysis_job (status);

-- 이 작업으로 생긴 활동. 활동은 합치기로 지워질 수 있어 activity 에 외래 키를 걸지 않는다
create table analysis_job_activity (
    job_id      bigint  not null references analysis_job (id) on delete cascade,
    position    integer not null,
    activity_id bigint  not null,
    primary key (job_id, position)
);

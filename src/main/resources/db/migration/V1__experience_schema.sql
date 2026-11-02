-- 원본 · 활동 · 경험 · 근거 (SPEC R-01 용어 표, ETC docs/contracts/experience-api.md)
-- 활동 하나에 경험 여럿, 경험 하나에 근거 여럿, 근거는 원본을 가리킨다.

create table app_user (
    id         bigint primary key,
    name       text        not null,
    created_at timestamptz not null default now()
);

-- SAMPLE(fixed-user): 로그인(R-02, BE#10) 전까지 모든 데이터는 이 사용자 하나의 것이다
insert into app_user (id, name) values (1, '고정 사용자');

create table source (
    id         bigserial primary key,
    user_id    bigint      not null references app_user (id),
    kind       varchar(10) not null check (kind in ('TEXT', 'FILE')),
    name       text        not null,
    text       text        not null,
    created_at timestamptz not null default now()
);

create index source_user_id_idx on source (user_id);

-- 기간은 원본에 있는 만큼만: 'YYYY' · 'YYYY-MM' · 'YYYY-MM-DD' 문자열, 모르면 null
create table activity (
    id           bigserial primary key,
    user_id      bigint      not null references app_user (id),
    name         text        not null,
    period_start varchar(10),
    period_end   varchar(10),
    ongoing      boolean     not null default false,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create index activity_user_id_idx on activity (user_id);

-- 활동을 지울 때(합치기) 경험이 같이 사라지지 않게 cascade 를 걸지 않는다
create table experience (
    id            bigserial primary key,
    activity_id   bigint      not null references activity (id),
    content       text        not null,
    perspective   text,
    actor         varchar(10) not null check (actor in ('ME', 'TEAM', 'UNKNOWN')),
    source_status varchar(20) not null check (source_status in ('SOURCE_CONFIRMED', 'USER_ENTERED', 'NEEDS_REVIEW')),
    position      integer     not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index experience_activity_id_idx on experience (activity_id, position);

-- 경험을 지우면 근거도 지운다. 원본은 다른 경험의 근거일 수 있어 남긴다
create table citation (
    id            bigserial primary key,
    experience_id bigint      not null references experience (id) on delete cascade,
    source_id     bigint      not null references source (id),
    quote         text        not null,
    matched       boolean,
    start_line    integer,
    end_line      integer,
    created_at    timestamptz not null default now()
);

create index citation_experience_id_idx on citation (experience_id);

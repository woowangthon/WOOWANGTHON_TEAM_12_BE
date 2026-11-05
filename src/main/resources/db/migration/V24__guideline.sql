-- 작성 지침 · 구성 틀(SPEC S-49 · S-50, ETC docs/contracts/guideline-api.md · question-api.md 「답변 초안」). BE#83

-- 사용자가 쌓은 지침 — 0개에서 시작한다. 켠 것 10개 · 전체 50개 한도는 BE 가 사용자 행을 잠그고 센다.
create table guideline (
    id         bigserial primary key,
    user_id    bigint      not null references app_user (id),
    text       text        not null,
    source     text        not null,
    enabled    boolean     not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index guideline_user_id_idx on guideline (user_id, id);

-- 초안의 구성 틀 — 틀이 생기기 전 초안은 NONE
alter table draft add column frame varchar(10) not null default 'NONE'
    check (frame in ('STAR', 'CARL', 'PREP', 'NONE'));

-- 초안에 실은 지침 — 만들 때의 글을 옮겨 둔다. 지침을 고치거나 지워도 남도록 외래 키를 걸지 않는다.
-- applied · note 는 AI 응답이 오기 전엔 null
create table draft_guideline (
    draft_id     bigint  not null references draft (id) on delete cascade,
    position     integer not null,
    guideline_id bigint  not null,
    text         text    not null,
    source       text    not null,
    applied      boolean,
    note         text,
    primary key (draft_id, position)
);

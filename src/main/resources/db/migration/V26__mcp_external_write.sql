-- 외부 AI 쓰기(SPEC S-52 · S-53, ETC docs/contracts/mcp-api.md 「외부 쓰기 도구」 · 「변경 기록」, ADR-010). BE#91
-- 쓰기를 켠 연결은 범위가 READ_WRITE. 원본 kind 의 MCP 는 BE 만 붙인다(POST /api/sources 는 받지 않는다).
alter table access_token drop constraint access_token_scope_check;
alter table access_token add constraint access_token_scope_check check (scope in ('READ', 'READ_WRITE', 'CHAGOK'));

alter table source drop constraint source_kind_check;
alter table source add constraint source_kind_check check (kind in ('TEXT', 'FILE', 'MCP'));

-- 외부 AI 가 바꾼 것 한 건. experience · source · job 은 FK 없이 ID 만 둔다 — 대상이 지워져도 기록은 남고 그 경험은 CHANGED_AFTER 로 본다.
create table mcp_change (
    id                   bigserial primary key,
    user_id              bigint      not null references app_user (id),
    type                 varchar(30) not null check (type in ('ADD_SOURCE', 'UPDATE_EXPERIENCE')),
    client               text        not null,
    status               varchar(20) not null check (status in ('APPLIED', 'CONFIRMED', 'UNDONE')),
    reason               text,
    created_at           timestamptz not null default now(),
    decided_at           timestamptz,
    -- ADD_SOURCE
    source_id            bigint,
    job_id               bigint,
    -- UPDATE_EXPERIENCE — 활동은 그때 값(경험이 지워져도 기록에 보이게)
    experience_id        bigint,
    activity_id          bigint,
    activity_name        text,
    before_content       text,
    after_content        text,
    before_source_status varchar(20)
);

create index mcp_change_user_id_created_at_idx on mcp_change (user_id, created_at desc);

-- 외부 AI 가 고쳐 사용자가 아직 정하지 않은 경험만 찬다 — Experience.mcpChange. 사용자가 고치거나 정하면 비운다
alter table experience add column mcp_change_id bigint;
alter table experience add column mcp_client text;

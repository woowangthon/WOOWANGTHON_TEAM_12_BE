-- S-91 · BE#173: 전문 · 이미지 대신 사용자가 확인한 요건 줄만 지원에 저장한다.
create table application_posting_line (
    id             bigserial primary key,
    application_id bigint not null references application (id) on delete cascade,
    position       integer not null check (position >= 0),
    section        varchar(20) not null check (section in ('DUTY', 'REQUIRED', 'PREFERRED', 'STACK')),
    text           varchar(300) not null check (length(btrim(text)) > 0),
    ksa            varchar(20) check (ksa in ('KNOWLEDGE', 'SKILL', 'ATTITUDE')),
    read_from      varchar(10) not null check (read_from in ('TEXT', 'URL', 'IMAGE')),
    unique (application_id, position)
);

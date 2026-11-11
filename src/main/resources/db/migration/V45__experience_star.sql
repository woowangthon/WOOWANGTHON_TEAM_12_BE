-- S-95 · S-96 · BE#175: 경험 STAR 칸 — 경험마다 정리 상태 하나와 칸 넷(S · T · A · R).
-- 칸은 그 경험의 근거 · 보완을 가리키기만 한다(source_type + source_id). 가리키던 것이 지워질 수 있어 외래 키 없이 ID 만 둔다.
-- experience 에도 외래 키를 걸지 않는다 — 경험을 지우는 길(JPA · 합치기 SQL)이 칸을 같이 지운다.
alter table experience
    add column star_status varchar(20) not null default 'NONE'
        check (star_status in ('NONE', 'ANALYZING', 'READY', 'STALE', 'FAILED'));

create index experience_star_analyzing_idx on experience (star_status) where star_status = 'ANALYZING';

create table experience_star_slot (
    experience_id bigint      not null,
    slot          varchar(1)  not null check (slot in ('S', 'T', 'A', 'R')),
    text          text,
    source_type   varchar(20) check (source_type in ('CITATION', 'SUPPLEMENT')),
    source_id     bigint,
    question      text,
    primary key (experience_id, slot),
    check ((source_type is null) = (source_id is null)),
    check ((text is null) = (source_id is null))
);

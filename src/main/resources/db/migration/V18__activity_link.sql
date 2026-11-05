-- 이어짐(SPEC S-35, ETC docs/contracts/experience-api.md 「이어짐 ActivityLink」). BE#47
-- 「from 활동의 계기가 to 활동으로 이어졌나」 한 쌍. 같은 쌍은 상태와 상관없이 하나뿐이다 —
-- REJECTED 도 지우지 않고 남겨 다시 제안하지 않는 데 쓴다. 활동이 지워지면 이어짐도 지워진다.

create table activity_link (
    id               bigserial primary key,
    from_activity_id bigint      not null references activity (id) on delete cascade,
    to_activity_id   bigint      not null references activity (id) on delete cascade,
    reason           text        not null,
    status           varchar(20) not null check (status in ('SUGGESTED', 'CONFIRMED', 'REJECTED')),
    created_at       timestamptz not null default now(),
    unique (from_activity_id, to_activity_id),
    check (from_activity_id <> to_activity_id)
);

create index activity_link_to_activity_id_idx on activity_link (to_activity_id);

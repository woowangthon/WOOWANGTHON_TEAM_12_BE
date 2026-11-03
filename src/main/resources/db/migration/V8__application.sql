-- 지원 = 지원처 하나 + 문항 여러 개(SPEC R-03 용어 · S-18 · S-21, ETC docs/contracts/question-api.md 「Application」). BE#30
-- 문항은 늘 어느 지원에 딸린다. 지원처 이름은 지원에만 둔다 — 문항의 organization 은 그 지원의 것.
-- 지원 안의 문항 순서는 넣은 순서(question.id 오름차순)다.

create table application (
    id           bigserial primary key,
    user_id      bigint      not null references app_user (id),
    organization text,
    created_at   timestamptz not null default now()
);

create index application_user_id_idx on application (user_id, created_at desc);

-- 이미 저장된 문항(BE#22 이후)은 문항마다 지원 하나씩 — 지원 ID 를 문항 ID 와 같게 만들어 붙인다
insert into application (id, user_id, organization, created_at)
select id, user_id, organization, created_at from question;
select setval(pg_get_serial_sequence('application', 'id'), greatest((select max(id) from application), 1),
              (select count(*) > 0 from application));

alter table question add column application_id bigint references application (id);
update question set application_id = id;
alter table question alter column application_id set not null;
alter table question drop column organization;

create index question_application_id_idx on question (application_id, id);

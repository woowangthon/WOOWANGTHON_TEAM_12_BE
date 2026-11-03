-- 로그인 · 사용자별 하루 분석 횟수 한도(SPEC R-02 · S-14 · S-15, ETC docs/contracts/auth-api.md). BE#10
-- V1 의 사용자 1 은 아이디 · 비밀번호가 없어 로그인할 수 없다 — 로그인 전 개발 데이터의 주인으로만 남는다.

create sequence app_user_id_seq owned by app_user.id;
select setval('app_user_id_seq', greatest((select max(id) from app_user), 1));
alter table app_user alter column id set default nextval('app_user_id_seq');

-- login_id 는 소문자로 맞춰 저장한다. daily_analysis_limit 이 null 이면 설정값(app.limit.daily-analyses)을 쓴다
alter table app_user
    add column login_id             varchar(30) unique,
    add column password_hash        text,
    add column daily_analysis_limit integer check (daily_analysis_limit >= 0),
    add constraint app_user_login_check check ((login_id is null) = (password_hash is null));

-- 하루(한국 날짜) 동안 시작한 분석 수 — 자료 넣기 · 분석 다시 시도 · 문항 넣기 · 문항 다시 시도마다 1
create table analysis_usage (
    user_id bigint  not null references app_user (id),
    day     date    not null,
    count   integer not null,
    primary key (user_id, day)
);

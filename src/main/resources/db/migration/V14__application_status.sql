-- 지원 상태 꼬리표(SPEC S-36)와 낸 달(S-37) — ETC docs/contracts/question-api.md 「지원 Application」. BE#63
-- 기존 지원은 모두 작성 중으로 시작한다.

alter table application
    add column application_status varchar(20) not null default 'WRITING'
        check (application_status in ('WRITING', 'SUBMITTED', 'PASSED', 'NOT_PASSED', 'UNKNOWN')),
    add column submitted_month varchar(7)
        check (submitted_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    -- 작성 중이면 낸 달이 없고, 작성 중을 벗어나면 늘 있다
    add constraint application_submitted_month_present
        check ((application_status = 'WRITING') = (submitted_month is null));

-- S-93 · BE#174: 공고 기준 경험 찾기 — 지원 하나에 작업 하나(상태 · 오류는 지원에), 판정은 줄마다.
-- 받치는 경험은 그새 지워질 수 있어 외래 키 없이 ID 만 둔다(문항 요구사항과 같다).
alter table application
    add column posting_match_status        varchar(20) check (posting_match_status in ('ANALYZING', 'COMPLETED', 'FAILED')),
    add column posting_match_error_code    varchar(30),
    add column posting_match_error_message text;

create index application_posting_match_analyzing_idx on application (posting_match_status)
    where posting_match_status = 'ANALYZING';

alter table application_posting_line
    add column match_status         varchar(20) check (match_status in ('SUPPORTED', 'PARTIAL', 'NOT_FOUND')),
    add column match_experience_ids bigint[] not null default '{}';

-- 공고 되묻기는 문항 되묻기와 같은 표 · 같은 답하기 경로 — 문항 아니면 공고 줄 하나에 붙는다
alter table clarification
    alter column question_id drop not null,
    add column posting_line_id bigint references application_posting_line (id) on delete cascade,
    add constraint clarification_owner_check check ((question_id is null) <> (posting_line_id is null));

create index clarification_posting_line_id_idx on clarification (posting_line_id);

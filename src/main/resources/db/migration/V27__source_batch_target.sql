-- 자료 여러 개 한 번에(S-59) · 활동에 자료 더하기(S-60). BE#97

-- 차례를 기다리는 작업. 앞 작업이 끝나면 ANALYZING 이 된다
alter table analysis_job drop constraint analysis_job_status_check;
alter table analysis_job add constraint analysis_job_status_check
    check (status in ('QUEUED', 'ANALYZING', 'COMPLETED', 'FAILED'));

-- 자료를 더할 활동. 활동은 지워지거나 합쳐질 수 있어 넣을 때의 값을 그대로 두고 외래 키를 걸지 않는다
alter table analysis_job add column target_activity_id bigint;
-- COMPLETED 이고 대상이 있을 때만 값이 있다
alter table analysis_job add column target_matched boolean;

-- 진행 중인 일(SPEC S-47, ETC docs/contracts/work-api.md). BE#77

-- 소식을 어디까지 봤나 — 계정 단위. 한 번도 안 봤으면 null
alter table app_user add column work_seen_at timestamptz;

-- 차곡이 일이 끝난 시각. 이미 끝난 일은 정확한 시각이 없어 시작 시각으로 채운다
alter table chagok_run add column finished_at timestamptz;
update chagok_run set finished_at = created_at where status in ('DONE', 'FAILED', 'CANCELED');

-- 추천이 끝난 시각. updated_at 은 사용자가 유형을 고쳐도 바뀌어, 옛 일이 새 소식으로 보이게 된다
alter table question add column finished_at timestamptz;
update question set finished_at = updated_at where status in ('COMPLETED', 'FAILED');

-- GET /api/work 는 폴링으로 자주 불린다. 나머지 표는 user_id 인덱스나 question 을 거쳐 찾는다
create index chagok_run_user_id_idx on chagok_run (user_id, created_at desc);

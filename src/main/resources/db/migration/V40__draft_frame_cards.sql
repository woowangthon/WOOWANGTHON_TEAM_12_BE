-- 구성 틀 값 목록에 구조 카드 6개를 더한다(SPEC S-82 — ETC docs/contracts/README.md `draftFrame`). BE#150
-- MOTIVE_LINK · SKILL_STEPS 가 11자라 열도 넓힌다. 예전 초안의 틀은 바꾸지 않는다
alter table draft drop constraint draft_frame_check;
alter table draft alter column frame type varchar(20);
alter table draft add constraint draft_frame_check
    check (frame in ('STAR', 'CARL', 'PREP', 'MOTIVE_LINK', 'CAUSE_FIX', 'ALIGN', 'CHANGE', 'SKILL_STEPS', 'ORIGIN', 'NONE'));

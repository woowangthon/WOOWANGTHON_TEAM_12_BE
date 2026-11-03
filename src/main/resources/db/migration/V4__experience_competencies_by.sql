-- 역량 출처(competenciesBy) — 지금 competencies 를 누가 붙였나(AI · USER). BE#25 · ETC#57
-- 이미 있는 행: 근거가 있거나 「내가 입력」이 아니면 분석이 만든 경험이라 AI, 근거 없는 「내가 입력」은 직접 추가한 경험이라 USER.
-- (본문만 고친 AI 경험도 근거가 남아 있어 AI 가 된다)

alter table experience add column competencies_by varchar(16);

update experience e set competencies_by = case
    when e.source_status <> 'USER_ENTERED' then 'AI'
    when exists (select 1 from citation c where c.experience_id = e.id) then 'AI'
    else 'USER'
end;

alter table experience alter column competencies_by set not null;
alter table experience add constraint experience_competencies_by_check check (competencies_by in ('AI', 'USER'));

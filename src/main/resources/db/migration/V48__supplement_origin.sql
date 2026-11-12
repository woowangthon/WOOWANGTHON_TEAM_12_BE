-- BE#190 · S-96: 칸을 비울 때 STAR 에서 만든 글만 같이 지우도록 입구를 기억한다.
alter table supplement add column origin varchar(20);

-- 기존에는 이 세 연결이 없는 보완을 만드는 입구가 StarService 하나였다.
-- 앞으로는 연결이 지워지거나 외부 AI 답을 확인해도 origin 을 바꾸지 않는다.
update supplement set origin = case
    when mcp_change_id is not null then 'EXTERNAL_AI'
    when clarification_id is null and question_id is null then 'STAR'
    else 'CLARIFICATION'
end;

alter table supplement alter column origin set not null;
alter table supplement alter column origin set default 'CLARIFICATION';
alter table supplement add constraint supplement_origin_check
    check (origin in ('STAR', 'CLARIFICATION', 'EXTERNAL_AI'));

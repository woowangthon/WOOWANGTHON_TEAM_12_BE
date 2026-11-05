-- 초안의 빠진 사실에 답하고 다시 만들기(SPEC S-27 · ETC#139). BE#71
-- omitted 를 문자열에서 { text, question, experienceId } 로 바꾸고, 경험에 붙은 것마다 되묻기를 만든다.

-- 초안에서 만든 되묻기 — 추천의 되묻기와 같은 ID 공간 · 같은 답 저장 경로. draft_id 가 있으면 초안 것이다.
-- 다시 만들면 BE 가 지운다(답한 보완은 supplement.clarification_id 가 null 이 될 뿐 남는다)
alter table clarification add column draft_id bigint references draft (id) on delete cascade;
create index clarification_draft_id_idx on clarification (draft_id);

-- 경험에 붙지 않는 빈칸은 question · experience_id · clarification_id 가 모두 null
alter table draft_omitted
    add column question         text,
    add column experience_id    bigint,
    add column clarification_id bigint;

-- 옛 문자열 형식 초안 — 경험 · 질문을 추측하지 않고 draft: null 로 돌려 사용자가 다시 만들게 한다
alter table draft add column legacy_omitted boolean not null default false;
update draft set legacy_omitted = true where id in (select draft_id from draft_omitted);

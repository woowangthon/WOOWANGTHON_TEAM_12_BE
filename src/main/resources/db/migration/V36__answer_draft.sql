-- 쓰는 중인 답 임시 글(SPEC S-72, ETC docs/contracts/question-api.md 「임시 글」). BE#122
-- 문항마다 하나 — 다시 저장하면 같은 행을 바꾸고, 확정하면 지운다. 문항이 지워지면 같이 지운다.
-- state 는 FE 가 정하는 답칸 화면 상태 JSON 글이다 — BE 는 해석하지 않는다.

create table answer_draft (
    id          bigserial primary key,
    question_id bigint      not null unique references question (id) on delete cascade,
    text        text        not null,
    state       text,
    updated_at  timestamptz not null
);

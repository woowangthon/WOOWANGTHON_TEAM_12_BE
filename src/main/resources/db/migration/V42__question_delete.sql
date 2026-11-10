-- 지원 · 문항 지우기(SPEC S-89, ETC docs/contracts/question-api.md 「문항 지우기」). BE#156
-- 새 경험 후보로 만든 경험은 문항을 지워도 origin 이 그대로다(type · questionId · applicationId) — 지원처 이름 · 문항 글만 null.
-- 그래서 origin_question_id 의 외래 키(on delete set null)를 풀고, 문항이 사라져도 지원을 알도록 origin_application_id 를 둔다.

alter table experience drop constraint experience_origin_question_id_fkey;
alter table experience add column origin_application_id bigint;
update experience e set origin_application_id = q.application_id from question q where q.id = e.origin_question_id;

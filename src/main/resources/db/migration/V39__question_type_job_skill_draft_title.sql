-- 문항 유형 JOB_SKILL(직무 역량 · 직무 준비, SPEC S-85)과 초안 첫 줄 소제목 title(S-86) — ETC docs/contracts/README.md · question-api.md. BE#149

-- 문항 유형 목록에 JOB_SKILL 을 더한다. 예전에 붙은 유형은 바꾸지 않는다(다시 매칭하면 새로 붙는다)
alter table question drop constraint question_question_type_check;
alter table question add constraint question_question_type_check
    check (question_type in ('MOTIVATION', 'SELF_INTRO', 'STRENGTH_WEAKNESS', 'ACHIEVEMENT', 'PROBLEM_SOLVING',
                             'COLLABORATION', 'CONFLICT', 'FAILURE', 'GROWTH', 'IDEA', 'JOB_SKILL', 'OTHER'));

-- AI 가 제안한 소제목 40자 이하. 문장이 0개 · 만드는 중 · 실패 · 옛 초안은 null
alter table draft add column title varchar(40);

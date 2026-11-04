-- 지원처 종류 · 직무(SPEC S-42)와 문항 유형(S-41) — ETC docs/contracts/README.md 「값 목록」. BE#50
-- 셋 다 닫힌 목록이고 선택이라 null 일 수 있다. 문항 유형은 매칭이 붙이고(AI) 사용자가 고친다(USER — 매칭이 덮지 않는다).

alter table application
    add column organization_type varchar(20)
        check (organization_type in ('CLUB', 'EXTRACURRICULAR', 'EXTERNAL', 'BOOTCAMP', 'INTERN', 'JOB', 'OTHER')),
    add column job_role varchar(20)
        check (job_role in ('BACKEND', 'FRONTEND', 'MOBILE', 'AI_DATA', 'INFRA', 'DESIGN', 'PLANNING', 'MARKETING',
                            'BUSINESS', 'OTHER'));

alter table question
    add column question_type varchar(20)
        check (question_type in ('MOTIVATION', 'SELF_INTRO', 'STRENGTH_WEAKNESS', 'ACHIEVEMENT', 'PROBLEM_SOLVING',
                                 'COLLABORATION', 'CONFLICT', 'FAILURE', 'GROWTH', 'IDEA', 'OTHER')),
    add column question_type_by varchar(10) check (question_type_by in ('AI', 'USER')),
    -- 유형이 있으면 출처도 있다
    add constraint question_type_by_present check ((question_type is null) = (question_type_by is null));

-- 경험의 관점(perspective, 문자열 하나) → 역량 배열(competencies). SPEC 미결정 2 · ETC#46 · BE#15
-- 값은 닫힌 목록 13개(contracts/README 「값 목록」), 0 ~ 3개, 중복 없음, 저장한 순서가 곧 맞는 순서다.

alter table experience add column competencies text[] not null default '{}';

-- 있던 관점은 목록의 한국어 이름과 같은 것만 옮기고 나머지는 [] 로 둔다
update experience set competencies = case trim(perspective)
    when '기술 구현' then '{IMPLEMENTATION}'
    when '문제 해결' then '{PROBLEM_SOLVING}'
    when '기획'     then '{PLANNING}'
    when '분석'     then '{ANALYSIS}'
    when '운영'     then '{OPERATION}'
    when '협업'     then '{COLLABORATION}'
    when '소통'     then '{COMMUNICATION}'
    when '리더십'   then '{LEADERSHIP}'
    when '주도성'   then '{INITIATIVE}'
    when '책임감'   then '{RESPONSIBILITY}'
    when '끈기'     then '{PERSEVERANCE}'
    when '학습'     then '{LEARNING}'
    when '배운 점'  then '{LEARNING}'
    when '발표'     then '{PRESENTATION}'
    else '{}'
end::text[]
where perspective is not null;

alter table experience drop column perspective;

alter table experience add constraint experience_competencies_check check (
    cardinality(competencies) <= 3
    and competencies <@ array['IMPLEMENTATION', 'PROBLEM_SOLVING', 'PLANNING', 'ANALYSIS', 'OPERATION',
        'COLLABORATION', 'COMMUNICATION', 'LEADERSHIP', 'INITIATIVE', 'RESPONSIBILITY', 'PERSEVERANCE',
        'LEARNING', 'PRESENTATION']::text[]
);

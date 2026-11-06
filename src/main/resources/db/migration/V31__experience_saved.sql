-- 경험 저장(SPEC S-71, ETC docs/contracts/experience-api.md). BE#119
-- 사용자가 다시 쓸 경험에 켜 두는 불리언 하나. 새 경험과 기존 경험은 저장하지 않은 상태다.
alter table experience add column saved boolean not null default false;

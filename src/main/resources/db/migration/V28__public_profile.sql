-- 공개 프로필 링크(SPEC S-65 · S-66, ETC docs/contracts/share-api.md). BE#102
-- 고른 경험은 불리언 하나 — 사본을 두지 않고 공개 응답을 요청 때마다 지금 값으로 만든다. 기본은 아무것도 공개되지 않는다.
alter table experience add column shared boolean not null default false;

-- 사용자당 하나. token 이 null 이면 링크가 꺼져 있다. 주소를 다시 복사할 수 있어야 해서 원문으로 둔다(MCP 연결과 다르다).
create table public_profile (
    user_id bigint primary key references app_user (id),
    title   text,
    token   text unique
);

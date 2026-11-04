-- 접근 토큰 — 웹 로그인 세션 밖에서 사용자 데이터를 읽는 열쇠(SPEC R-04 · F-11 · S-32, ETC docs/contracts/mcp-api.md). BE#41
-- 지금은 MCP 연결 주소(/mcp/{token}) 하나만 쓴다 — API 는 사용자당 하나만 다루지만, 테이블은 사용자당 여러 행을 받는다.
-- 나중에 쓰기 권한(ETC#27) · 클라이언트별 연결 · REST 개인 토큰 · OAuth 발급 토큰이 같은 테이블에 scope · name 을 달고 들어온다.
-- 토큰 원문은 저장하지 않는다 — SHA-256 해시(16진수 64자)만. 그래서 주소는 만들 때 한 번만 보인다.

create table access_token (
    id           bigserial primary key,
    user_id      bigint      not null references app_user (id),
    token_hash   varchar(64) not null unique,
    scope        varchar(10) not null check (scope in ('READ')),
    name         text,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz
);

create index access_token_user_id_idx on access_token (user_id);

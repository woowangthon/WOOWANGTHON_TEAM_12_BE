-- 같은 자료 알아보기(SPEC S-69, ETC docs/contracts/experience-api.md 「같은 자료 알아보기」). BE#99
-- 받은 글자를 \r\n · \r → \n 으로 바꾸고 앞뒤 공백 · 줄바꿈을 뗀 뒤 UTF-8 로 SHA-256 한 16진수 64자. 비교에만 쓴다.
-- null 은 비교에서 빠진다 — 마이그레이션 전 원본은 아래에서 채운다.
alter table source add column content_hash varchar(64);

-- 앞뒤 공백은 Java String.strip() 과 같게 보려 했으나 SQL 로는 흔한 것만 뗀다(공백 · 탭 · 줄바꿈 · 전각 공백).
-- 어긋나는 옛 원본은 같은 자료로 알아보지 못할 뿐 — 안내일 뿐이라 「그래도 넣기」로 넘어간다
update source
set content_hash = encode(sha256(convert_to(
        btrim(replace(replace(text, E'\r\n', E'\n'), E'\r', E'\n'), E' \t\n\u000B\u000C　'), 'UTF8')), 'hex');

create index source_user_content_hash_idx on source (user_id, content_hash);

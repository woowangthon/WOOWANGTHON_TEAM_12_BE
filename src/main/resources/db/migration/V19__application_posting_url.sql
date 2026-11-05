-- 공고 원문 링크(SPEC S-46) — ETC docs/contracts/question-api.md 「지원 Application」 postingUrl. BE#61
-- 넣을 때만 받고 고치지 않는다. 서버는 이 링크를 다시 읽지 않는다(본문 · 요약을 저장하지 않는다).

alter table application
    add column posting_url varchar(2000)
        check (posting_url ~* '^https?://');

-- 원본의 쪽 · 슬라이드 구간(SPEC S-39, ETC docs/contracts/experience-api.md 「자료 넣기」 sections). BE#48
-- .pdf · .pptx 원본만 가진다 — FE 가 브라우저에서 글자를 뽑으며 계산해 보낸 그대로 남긴다.
-- 근거를 돌려줄 때 AI 가 준 줄 번호를 여기에 대어 location.section(「2쪽」 · 「슬라이드 4」)을 싣는다.
-- 기존 원본은 구간이 없다 — 근거의 section 은 null(데이터 옮기기 없음).

create table source_section (
    source_id  bigint      not null references source (id) on delete cascade,
    kind       varchar(10) not null check (kind in ('PAGE', 'SLIDE')),
    number     int         not null check (number >= 1),
    start_line int         not null check (start_line >= 1),
    end_line   int         not null,
    primary key (source_id, start_line),
    check (start_line <= end_line)
);

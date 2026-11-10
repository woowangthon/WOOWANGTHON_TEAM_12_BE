-- 초안 AI 가정(SUPPOSED) 문장 · specific · 짝 되묻기(SPEC S-78, ETC ADR-014). BE#144

-- SUPPOSED 문장 — 자료에 없는 장면을 그럴 법하게 쓴 문장. 근거가 0개여도 된다
alter table draft_sentence drop constraint draft_sentence_kind_check;
alter table draft_sentence add constraint draft_sentence_kind_check
    check (kind in ('FACT', 'OPINION', 'ELABORATED', 'SUPPOSED'));

-- specific — SUPPOSED 만 true · false(수치 · 수상 · 직책 · 고유명사가 들어간 가정). 나머지 · 옛 문장은 null
alter table draft_sentence add column specific boolean;
alter table draft_sentence add constraint draft_sentence_specific_check check ((kind = 'SUPPOSED') = (specific is not null));

-- SUPPOSED 의 짝 omitted 는 placeholder null · sentence_index 있음. 자리 표시가 있으면 순번도 있어야 한다
alter table draft_omitted drop constraint draft_omitted_placeholder_pair_check;
alter table draft_omitted add constraint draft_omitted_placeholder_pair_check
    check (placeholder is null or sentence_index is not null);

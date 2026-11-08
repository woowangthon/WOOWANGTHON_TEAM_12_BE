-- 초안 재료 · 목표 글자 수 · AI 보탬 · 문장 속 빈칸(SPEC S-75 · S-77 · S-78, ETC docs/contracts/question-api.md). BE#125

-- 문항의 목표 글자 수 — 사용자가 PATCH 로 정한다. 없으면 null
alter table question add column target_length integer check (target_length between 100 and 5000);

-- 같은 활동의 다른 경험도 뒷받침 재료로 썼나(요청 값 그대로). 이 칸이 생기기 전 초안은 썼다(true)
alter table draft add column use_context boolean not null default true;

-- AI 보탬(ELABORATED) — 왜 확인이 필요한지 한 줄. ELABORATED 만 있고 나머지는 null. 옛 문장은 null
alter table draft_sentence drop constraint draft_sentence_kind_check;
alter table draft_sentence add constraint draft_sentence_kind_check check (kind in ('FACT', 'OPINION', 'ELABORATED'));
alter table draft_sentence add column note varchar(60);
alter table draft_sentence add constraint draft_sentence_note_check check ((kind = 'ELABORATED') = (note is not null));

-- 문장 속 빈칸 — 자리 표시와 그 문장 순번은 둘 다 있거나 둘 다 null. 옛 빈칸은 null
alter table draft_omitted
    add column placeholder    varchar(12),
    add column sentence_index integer;
alter table draft_omitted add constraint draft_omitted_placeholder_pair_check
    check ((placeholder is null) = (sentence_index is null));

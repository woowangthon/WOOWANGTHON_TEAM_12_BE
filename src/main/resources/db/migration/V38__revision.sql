-- 수정 이력(SPEC S-80 · ADR-013, ETC docs/contracts/revision-api.md). BE#127
-- 경험 · 활동 · 답을 고칠 때마다 바뀌기 전 · 후 스냅샷을 통째로 남긴다. 지우지 않는다 — 개수 · 기간 제한이 없다.
-- 대상(경험 · 활동 · 문항)을 지워도 이력은 남아야 해서 대상에는 외래 키를 걸지 않는다(target_id 는 그냥 번호).
-- target_* 는 기록한 때의 이름 — 목록 한 줄을 그리는 값이라 뒤에 이름이 바뀌어도 그대로다.
-- before_snapshot · after_snapshot 은 대상별 JSON 글이다(경험 · 활동 · 답의 스냅샷 필드는 계약 「스냅샷」).

create table revision (
    id                    bigserial primary key,
    user_id               bigint      not null references app_user (id),
    target_type           text        not null,
    target_id             bigint      not null,
    kind                  text        not null,
    changed_by            text        not null,
    client                text,
    mcp_change_id         bigint,
    restored_from_id      bigint references revision (id),
    created_at            timestamptz not null,
    target_activity_id    bigint,
    target_activity_name  text,
    target_question_id    bigint,
    target_question_text  text,
    target_application_id bigint,
    target_organization   text,
    before_snapshot       text        not null,
    after_snapshot        text        not null
);

-- 「최근 수정」(전체) · 「수정 이력」(그 항목만) — 둘 다 새것 먼저
create index revision_user_recent on revision (user_id, created_at desc, id desc);
create index revision_user_target on revision (user_id, target_type, target_id, created_at desc, id desc);

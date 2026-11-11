-- BE#180 · ADR-019: 한 번 정리한 STAR 는 다시 정리하지 않는다 — STALE 을 더 만들지 않는다.
-- 있던 STALE 은 칸이 이미 실제 글만 가리키므로(사라진 글을 가리키던 칸은 빈칸) READY 로 돌린다. 값은 계약에 남아 있어 check 는 그대로 둔다.
update experience set star_status = 'READY' where star_status = 'STALE';

-- 정리 안 한 경험(NONE)을 StarSweeper 가 활동 단위로 찾는다
create index experience_star_none_idx on experience (activity_id) where star_status = 'NONE';

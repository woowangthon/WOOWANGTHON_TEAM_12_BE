-- BE#184: 마지막 STAR 정리 뒤 칸 밖에 새 답 · 근거가 생겼나(계약 Star.hasNew). 다시 나누기 · 다시 시도가 READY 로 끝나면 끈다
alter table experience add column star_has_new boolean not null default false;

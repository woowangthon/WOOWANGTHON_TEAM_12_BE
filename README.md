# WOOWANGTHON_TEAM_12_BE

2026 AI 우왕톤 12팀 「내가 우왕이 될 상인가」 — 백엔드

전체 개요 · 명세 · 아키텍처 · 결정 기록은 [ETC 레포](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC).

## 스택

- Spring Boot 4.0.0 · Java 25 · Gradle 9.8.2 (Kotlin DSL, wrapper)
- Spring Data JPA (Hibernate) · Flyway
- PostgreSQL 18 — Docker 이미지 `pgvector/pgvector:pg18` (pgvector 확장은 아직 켜지 않음, ETC ADR-003)
- 테스트: Testcontainers

## 실행 방법

준비물: JDK 25 (`JAVA_HOME` 이 JDK 25 를 가리켜야 한다) · Docker.

```bash
docker compose -f compose-local.yaml up -d   # 로컬 DB 만 띄운다
./gradlew bootRun                            # Windows: gradlew.bat bootRun
```

- 앱: http://localhost:8080 · API 는 `/api` 아래.
- `compose-local.yaml` 은 DB 전용이다. 서버 배포용은 `docker-compose.yml` — 아래 「서버에 띄우기」.

### 포트가 겹치면

- DB(5432)를 이미 쓰고 있으면 — `compose-local.yaml` 의 `127.0.0.1:5432:5432` 앞쪽 번호를 `5433` 처럼 바꾸고, 앱은 `DB_URL=jdbc:postgresql://localhost:5433/woowang` 으로 실행한다(이 변경은 커밋하지 않는다).
- 앱(8080)을 이미 쓰고 있으면 — `SERVER_PORT=8081` 로 실행한다.

### 예시 데이터 켜기

기본은 꺼져 있다. 로컬에서 화면을 채우려면 `SEED_SAMPLE=true` 로 실행한다. 예시 데이터는 테스트 계정(아래 「테스트 계정」)에 들어가니 `TEST_ACCOUNT_PASSWORD` 도 같이 넣는다.

```bash
SEED_SAMPLE=true TEST_ACCOUNT_PASSWORD=local-test-pw ./gradlew bootRun
```

```powershell
$env:SEED_SAMPLE='true'; $env:TEST_ACCOUNT_PASSWORD='local-test-pw'; .\gradlew.bat bootRun   # Windows PowerShell
```

테스트 계정에 활동이 없을 때만 계약 예시를 한 번 넣는다(`SAMPLE(dev-seed-activities)`).

> **공개 전에 끄고 시드 데이터를 지운다.** 로컬 · Access 뒤 개발 서버에서만 켠다.

## API

계약: [ETC `docs/contracts/experience-api.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/experience-api.md)(자료 · 활동 · 경험) · [`question-api.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/question-api.md)(지원 · 문항 · 되묻기)

| 메서드 · 경로 | 하는 일 |
| --- | --- |
| `POST /api/sources` | 자료 넣기 — 원본 저장 · 분석 작업 시작(202, 분석은 뒤에서) |
| `GET /api/jobs/{id}` | 분석 작업 상태: `ANALYZING` → `COMPLETED` / `FAILED`(이유) |
| `POST /api/jobs/{id}/retry` | 실패한 작업 다시 시도 |
| `GET /api/activities` | 활동 목록 — 활동마다 분류(`category` · `categoryBy`, S-57). 분석이 붙이면 AI 해석, 미분류면 `null`. 정리용이라 MCP · AI 요청엔 싣지 않는다 |
| `GET /api/activities/{id}` | 활동 하나 |
| `PATCH /api/activities/{id}` | 활동 고치기 — 이름 · 기간 · 계기 · 분류(`category` — 바꾸면 `categoryBy: USER`, `null` 이면 미분류) |
| `GET /api/experiences` | 경험 찾기(S-58) — `query`(낱말 OR) · `competencies`(쉼표) · `year` · `sourceStatus` · `category` · `limit`(1 ~ 200, 기본 100), 조건은 AND. MCP `search_experiences` 와 같은 검색(`ExperienceSearch`). `{ total, experiences }` — 경험마다 `activity`(id · 이름 · 기간 · 분류) |
| `PATCH /api/experiences/{id}` | 경험 고치기 |
| `POST /api/activities/{id}/experiences` | 경험 직접 추가 — 「내가 입력」, 근거 없음, 맨 뒤에 붙음(201) |
| `DELETE /api/experiences/{id}` | 경험 지우기 — 근거 · 보완도 지우고 원본 · 활동은 남김(204) |
| `POST /api/experiences/{id}/move` | 경험 옮기기 — 속한 활동만 바꿈. 내용 · 근거 · 출처 상태는 그대로, 옮긴 활동의 맨 뒤 |
| `POST /api/activities/{id}/merge` | 활동 합치기 — `fromActivityId` 의 경험을 전부 이 활동으로 옮기고 그 활동을 지움(한 번에). 남길 활동이 미분류면 분류를 옮겨 온다 |
| `POST /api/activities/{id}/link-suggestions` | 이어짐 제안 받기 — 계기가 있는 활동에서 AI 가 고른 이어짐을 `SUGGESTED` 로 저장(동기, 후보 0개면 AI 를 안 부름) |
| `PATCH /api/activity-links/{id}` | 이어짐 정하기 — `CONFIRMED`(맞아요) · `REJECTED`(아니에요, 다시 제안하지 않음) |
| `POST /api/applications` | 지원 넣기 — 지원처 + 문항 1 ~ 10개 저장 · 문항마다 추천 작업 시작(202) |
| `GET /api/applications` | 넣었던 지원 목록(최근 것부터) |
| `GET /api/applications/{id}` | 지원 하나 — 문항 목록 · 분석 상태 |
| `POST /api/applications/{id}/questions` | 지원에 문항 더하기(202) — 지원 하나에 최대 20개 |
| `PATCH /api/applications/{id}` | 지원처 종류 · 직무 · 지원 상태 · 낸 달 바꾸기(보낸 칸만, 추천은 다시 돌지 않는다) |
| `POST /api/questions` | 지원 문항 넣기 — 문항 저장 · 추천 작업 시작(202, AI `/match` 는 뒤에서) |
| `GET /api/questions` | 넣었던 문항 목록(최근 것부터) |
| `GET /api/questions/{id}` | 문항 하나: `ANALYZING` → `COMPLETED`(요구사항 · 추천 · 되묻기) / `FAILED`(이유) |
| `POST /api/questions/{id}/retry` | 실패한 문항 다시 시도 |
| `PATCH /api/questions/{id}` | 문항 유형 고르기 — `USER` 로, 그 뒤 매칭 · 다시 시도가 덮지 않는다 |
| `POST /api/clarifications/{id}/answer` | 되묻기에 답하기 — 답을 그 경험의 보완으로 저장(201). 경험 본문 · 근거 · 출처 상태는 그대로, 다음 문항부터 AI 후보에 실림 |
| `GET /api/questions/{id}/similar-answers` | 비슷한 확정 답변(S-43) — 문항 유형 → 직무 → 지원처 종류가 같은 순서로 예전에 확정한 답 최대 5개. 참고용(근거 아님) · AI 안 부름 · 분석 횟수 안 씀 |
| `PATCH /api/supplements/{id}` | 보완 고치기 — 답만 바꿈. 질문 · 경험 본문 · 근거 · 출처 상태는 그대로 |
| `DELETE /api/supplements/{id}` | 보완 지우기(204) — 경험 · 다음 문항의 AI 후보 · 끝난 추천의 `evidence` 에서 빠지고, 그 되묻기에 다시 답할 수 있다 |

분석은 AI 서버(`AI_BASE_URL`, [ai-api 계약](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/ai-api.md))를 부른다. 로컬에서 끝까지 돌려 보려면 AI 레포를 8000 포트로 띄운다(그 레포 README). AI 서버가 없으면 작업은 `FAILED`(`AI_UNAVAILABLE`)가 된다 — 정해진 결과로 대신 채우지 않는다. 분석 중에 BE 를 다시 켜면 그 작업은 `FAILED` 가 되고 다시 시도할 수 있다.

### 로그인 · 하루 한도

계약: [ETC `docs/contracts/auth-api.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/auth-api.md)

| 메서드 · 경로 | 하는 일 |
| --- | --- |
| `POST /api/auth/signup` | 가입(아이디 · 비밀번호) — 바로 로그인된다(201) |
| `POST /api/auth/login` | 로그인 — 세션 쿠키(`JSESSIONID`)를 준다 |
| `POST /api/auth/logout` | 로그아웃(204) |
| `GET /api/auth/me` | 내 아이디 · 오늘 쓴 분석 횟수 / 한도 |

- 위 가입 · 로그인 · 로그아웃 말고는 전부 로그인해야 쓴다 — 아니면 401 `UNAUTHENTICATED`. 원본 · 활동 · 경험 · 지원 · 문항은 사용자별로 나뉜다.
- 분석을 시작하는 곳이 하루 횟수를 쓴다 — `POST /api/sources` · `/api/jobs/{id}/retry` · `POST /api/questions` · `/api/questions/{id}/retry` · `POST /api/applications/{id}/questions` 는 1회씩, `POST /api/applications` 는 문항 수만큼(남은 횟수가 모자라면 아무것도 저장하지 않는다). 다 쓰면 429 `DAILY_LIMIT_EXCEEDED` 로 시작하지 않는다. 한도는 `DAILY_ANALYSIS_LIMIT`(기본 20), 한국 시간 자정에 다시 채워진다.
- 입력 크기 한도: 자료 텍스트 50000자 · 문항 5000자(계약).
- 세션은 BE 메모리에 있다 — BE 를 다시 켜면 다들 다시 로그인한다.

### 다른 AI 에서 쓰기 — MCP 서버

계약: [ETC `docs/contracts/mcp-api.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/mcp-api.md) (S-32, ADR-008)

| API | 하는 일 |
| --- | --- |
| `GET /api/mcp/connection` | 연결 상태 — 쓰기를 켰나(`writeEnabled`) · 만든 시각 · 마지막으로 MCP 요청이 온 시각 |
| `POST /api/mcp/connection` | 연결 주소 만들기 · 다시 만들기(201) — 주소(`url`)는 이 응답에만. 예전 주소는 바로 401, 새 연결은 쓰기가 꺼진 채 시작 |
| `PATCH /api/mcp/connection` | 쓰기 켜기 · 끄기(S-52) — 주소는 그대로, 다음 MCP 요청부터 적용 |
| `DELETE /api/mcp/connection` | 연결 끊기(204) |
| `POST /mcp/{token}` | MCP 엔드포인트(JSON-RPC, 로그인 세션 무관) — 읽기 도구 `search_experiences` · `list_activities` · `get_activity`. 쓰기를 켠 연결은 `add_source` · `update_experience` 를 바로 실행 — 고친 경험은 `NEEDS_REVIEW`, 변경 기록(`mcp_change`)이 남는다 |

- 주소 앞부분은 `MCP_BASE_URL`(기본 `http://localhost:8080/mcp/`). 서버에선 공개 API 주소로 넣는다 — 요청 호스트로 짐작하지 않는다.
- 토큰은 해시만 저장한다 — 잃어버리면 다시 만든다. 주소가 곧 비밀번호라 로그에 남기지 않는다(접근 로그를 켜지 않았다).
- 로컬 확인: 로그인한 뒤 `POST /api/mcp/connection` 으로 주소를 받아 `claude mcp add --transport http woowang <주소>` → 「WebSocket 경험 찾아 줘」.
- claude.ai 커넥터는 Anthropic 서버에서 부르므로 서버에선 Cloudflare Access 가 `/mcp/*` 를 통과시켜야 한다(사람이 설정).

### 테스트 계정 (`SAMPLE(test-account)`)

`TEST_ACCOUNT_PASSWORD` 를 넣고 켜면 테스트 계정이 생긴다(켤 때마다 비밀번호 · 한도를 설정값으로 맞춘다). 데이터는 미리 넣지 않는다 — 진짜 흐름을 그대로 탄다.

| 아이디 | 비밀번호 | 하루 분석 |
| --- | --- | --- |
| `test` | **제출 폼에만** 적는다 — 이 레포는 Public 이라 여기 적으면 누구나 하루 200회 분석을 쓸 수 있다(#34) | 200회 |

비밀번호는 서버 `.env` 와 제출 폼에만 둔다. 팀원에게는 DM 으로 — README · 이슈 · 채널에는 적지 않는다.

`SEED_SAMPLE=true` 면 예시 데이터(`SAMPLE(dev-seed-activities)`)를 이 계정에 넣는다 — 그래서 로컬에서 예시 데이터를 보려면 테스트 계정 비밀번호도 같이 넣는다.

V1 마이그레이션의 사용자 1(`SAMPLE(fixed-user)`)은 아이디 · 비밀번호가 없어 로그인할 수 없다 — 로그인 전 개발 데이터의 주인으로만 남는다.

## 환경변수

변수와 기본값은 [`.env.example`](.env.example) 에 있다: `SEED_SAMPLE` · `CORS_ALLOWED_ORIGINS` · `DAILY_ANALYSIS_LIMIT` · `TEST_ACCOUNT_LOGIN_ID` · `TEST_ACCOUNT_PASSWORD` · `TEST_ACCOUNT_DAILY_LIMIT` · `SESSION_COOKIE_SECURE` · `SESSION_COOKIE_SAME_SITE` · `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `AI_BASE_URL` · `AI_TIMEOUT_SECONDS` · `AI_SYNC_TIMEOUT_SECONDS` · `MCP_BASE_URL` · `MCP_INTERNAL_BASE_URL` · `MCP_DAILY_WRITE_LIMIT` · `CHAGOK_TIMEOUT_SECONDS` · `CHAGOK_MAX_TOOL_CALLS` · (서버) `TUNNEL_TOKEN` · `AI_DIR`. `.env` 는 커밋하지 않는다.

## 서버에 띄우기

OCI 서버 하나에 Docker Compose 로 DB · AI · BE 와 Cloudflare Tunnel 을 띄운다(ETC ADR-004). 밖에서 들어오는 길은 Tunnel → BE 하나뿐이고, 공개 전엔 Cloudflare Access 로 팀원만 통과시킨다. 서버 · 도메인 · 비밀값은 사람이 다룬다.

```
인터넷 ─ Cloudflare(Access) ─ Tunnel ─ cloudflared ─▶ be:8080 ─▶ ai:8000 (내부망)
                                                        └──────▶ db:5432 (내부망)
```

### 1. 처음 한 번 — 서버 준비

Docker(Compose 플러그인 포함)가 깔린 서버에서 두 레포를 **같은 상위 폴더**에 클론한다. ARM 서버면 그대로 빌드해도 arm64 이미지가 된다.

```bash
git clone https://github.com/woowangthon/WOOWANGTHON_TEAM_12_BE.git
git clone https://github.com/woowangthon/WOOWANGTHON_TEAM_12_AI.git
cp WOOWANGTHON_TEAM_12_BE/.env.example WOOWANGTHON_TEAM_12_BE/.env
cp WOOWANGTHON_TEAM_12_AI/.env.example WOOWANGTHON_TEAM_12_AI/.env
```

| 파일 | 채울 것 |
| --- | --- |
| BE `.env` | `DB_PASSWORD`(새로 만든 긴 값) · `SEED_SAMPLE`(Access 뒤 개발 서버면 `true`) · `CORS_ALLOWED_ORIGINS`(FE 주소) · `TEST_ACCOUNT_PASSWORD`(심사용 테스트 계정) · `TUNNEL_TOKEN`(3단계) |
| AI `.env` | `OPENAI_API_KEY`(팀 키). 나머지는 그 레포 `.env.example` 기본값 |

BE `.env` 의 `DB_URL` · `AI_BASE_URL` 은 서버에선 쓰이지 않는다 — compose 가 `db` · `ai` 서비스 이름으로 정한다.

### 2. 앱 띄우기 (Tunnel 전)

```bash
cd WOOWANGTHON_TEAM_12_BE
docker compose up -d --build db ai be
docker compose ps                                # db · ai 가 healthy, be 가 running
curl -i http://127.0.0.1:8080/api/auth/me          # 서버 안에서만 열린다. 로그인 전이라 401 이면 정상
docker compose logs -f be                        # 로그
```

### 3. Cloudflare Tunnel

1. Cloudflare Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared. 설치 명령에 나오는 토큰(`eyJ…`)만 BE `.env` 의 `TUNNEL_TOKEN` 에 넣는다(서버에 cloudflared 를 따로 설치하지 않는다)
2. Public hostname: `api-<앱이름>.<도메인>` → Service `HTTP` · `be:8080`
3. `docker compose up -d cloudflared` → 시크릿 창에서 `https://api-<앱이름>.<도메인>/api/activities`

주소는 한 단계 서브도메인으로 둔다(`api-<앱이름>.<도메인>`). `api.<앱이름>.<도메인>` 처럼 두 단계면 Cloudflare 무료 인증서가 덮지 못해 HTTPS 오류가 난다.

### 4. Cloudflare Access — 공개 전 팀원만

Zero Trust → Access → Applications → Self-hosted, 주소 `api-<앱이름>.<도메인>`, 정책 Allow · 팀원 이메일. 시크릿 창에서 열면 로그인 화면이 먼저 나와야 한다.

### 고칠 때 · 끌 때

```bash
git pull && git -C ../WOOWANGTHON_TEAM_12_AI pull
docker compose up -d --build                     # 바뀐 이미지만 다시 빌드 · 재시작
docker compose down                              # 끄기(DB 데이터는 볼륨에 남는다)
```

> **공개 전**: BE `.env` 에 `SEED_SAMPLE=false`, DB 를 비우고 새로 시작(`docker compose down -v` — 볼륨까지 지운다, 되돌릴 수 없음) · BE `.env` 에 `TEST_ACCOUNT_PASSWORD`(DB 를 비운 뒤 켜면 테스트 계정이 다시 생긴다) · FE 로그인 화면이 붙은 뒤 Access 해제.

## 검증

```bash
./gradlew build
```

Testcontainers 통합 테스트가 돌아서 Docker 가 실행 중이어야 한다. JDK 25 필요.

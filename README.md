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

기본은 꺼져 있다. 로컬에서 화면을 채우려면 `SEED_SAMPLE=true` 로 실행한다.

```bash
SEED_SAMPLE=true ./gradlew bootRun
```

```powershell
$env:SEED_SAMPLE='true'; .\gradlew.bat bootRun   # Windows PowerShell
```

고정 사용자(1)에게 활동이 없을 때만 계약 예시를 한 번 넣는다(`SAMPLE(dev-seed-activities)`).

> **공개 전에 끄고 시드 데이터를 지운다.** 로컬 · Access 뒤 개발 서버에서만 켠다.

## API

계약: [ETC `docs/contracts/experience-api.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/experience-api.md)

| 메서드 · 경로 | 하는 일 |
| --- | --- |
| `POST /api/sources` | 자료 넣기 — 원본 저장 · 분석 작업 시작(202, 분석은 뒤에서) |
| `GET /api/jobs/{id}` | 분석 작업 상태: `ANALYZING` → `COMPLETED` / `FAILED`(이유) |
| `POST /api/jobs/{id}/retry` | 실패한 작업 다시 시도 |
| `GET /api/activities` | 활동 목록 |
| `GET /api/activities/{id}` | 활동 하나 |
| `PATCH /api/activities/{id}` | 활동 고치기 |
| `PATCH /api/experiences/{id}` | 경험 고치기 |
| `POST /api/activities/{id}/experiences` | 경험 직접 추가 — 「내가 입력」, 근거 없음, 맨 뒤에 붙음(201) |
| `DELETE /api/experiences/{id}` | 경험 지우기 — 근거도 지우고 원본 · 활동은 남김(204) |

분석은 AI 서버(`AI_BASE_URL`, [ai-api 계약](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/ai-api.md))를 부른다. 로컬에서 끝까지 돌려 보려면 AI 레포를 8000 포트로 띄운다(그 레포 README). AI 서버가 없으면 작업은 `FAILED`(`AI_UNAVAILABLE`)가 된다 — 정해진 결과로 대신 채우지 않는다. 분석 중에 BE 를 다시 켜면 그 작업은 `FAILED` 가 되고 다시 시도할 수 있다.

로그인(BE#10) 전까지 모든 데이터는 고정 사용자 1 의 것이다(`SAMPLE(fixed-user)`).

## 환경변수

변수와 기본값은 [`.env.example`](.env.example) 에 있다: `SEED_SAMPLE` · `CORS_ALLOWED_ORIGINS` · `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `AI_BASE_URL` · `AI_TIMEOUT_SECONDS` · (서버) `TUNNEL_TOKEN` · `AI_DIR`. `.env` 는 커밋하지 않는다.

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
| BE `.env` | `DB_PASSWORD`(새로 만든 긴 값) · `SEED_SAMPLE`(Access 뒤 개발 서버면 `true`) · `CORS_ALLOWED_ORIGINS`(FE 주소) · `TUNNEL_TOKEN`(3단계) |
| AI `.env` | `OPENAI_API_KEY`(팀 키). 나머지는 그 레포 `.env.example` 기본값 |

BE `.env` 의 `DB_URL` · `AI_BASE_URL` 은 서버에선 쓰이지 않는다 — compose 가 `db` · `ai` 서비스 이름으로 정한다.

### 2. 앱 띄우기 (Tunnel 전)

```bash
cd WOOWANGTHON_TEAM_12_BE
docker compose up -d --build db ai be
docker compose ps                                # db · ai 가 healthy, be 가 running
curl http://127.0.0.1:8080/api/activities        # 서버 안에서만 열린다
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

> **공개 전**: BE `.env` 에 `SEED_SAMPLE=false`, DB 를 비우고 새로 시작(`docker compose down -v` — 볼륨까지 지운다, 되돌릴 수 없음) · Access 해제는 로그인(BE#10)이 붙은 뒤.

## 검증

```bash
./gradlew build
```

Testcontainers 통합 테스트가 돌아서 Docker 가 실행 중이어야 한다. JDK 25 필요.

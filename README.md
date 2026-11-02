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
- `compose-local.yaml` 은 DB 전용이다. 서버 배포용 compose 는 따로 둔다(BE#9).

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
| `GET /api/activities` | 활동 목록 |
| `GET /api/activities/{id}` | 활동 하나 |
| `PATCH /api/activities/{id}` | 활동 고치기 |
| `PATCH /api/experiences/{id}` | 경험 고치기 |

로그인(BE#10) 전까지 모든 데이터는 고정 사용자 1 의 것이다(`SAMPLE(fixed-user)`).

## 환경변수

변수와 기본값은 [`.env.example`](.env.example) 에 있다: `SEED_SAMPLE` · `CORS_ALLOWED_ORIGINS` · `DB_URL` · `DB_USERNAME` · `DB_PASSWORD`. `.env` 는 커밋하지 않는다.

## 검증

```bash
./gradlew build
```

Testcontainers 통합 테스트가 돌아서 Docker 가 실행 중이어야 한다. JDK 25 필요.

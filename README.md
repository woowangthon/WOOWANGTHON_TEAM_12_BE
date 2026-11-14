# WOOWANGTHON_TEAM_12_BE

> 차곡의 백엔드 — 로그인 · 저장 · 분석 작업 · 하루 한도 · MCP 서버. **AI 서버를 부르는 유일한 곳**이다.

2026 AI 우왕톤 12팀 「내가 우왕이 될 상인가」. 서비스 **차곡**은 흩어진 기록에서 근거 달린 경험을 쌓아 두고, 지원할 때 맞는 경험을 꺼내 부족한 사실만 묻는다. 서비스 개요 · 명세 · 아키텍처 · 결정 기록은 [ETC 레포](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC), API 계약은 ETC [`docs/contracts/`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/tree/main/docs/contracts).

```mermaid
flowchart LR
    FE["FE · Next.js"] -->|"REST · 세션 쿠키"| BE["BE · Spring Boot (이 레포)"]
    EXT["사용자의 AI · Claude 등"] -->|"MCP · 연결 주소"| BE
    BE --> DB[("PostgreSQL")]
    BE -->|"내부망 REST"| AI["AI 서버 · FastAPI"]
    AI -->|"차곡이 도구 호출 · MCP"| BE
```

## 이 서버가 맡은 일

| 맡은 일 | 맡기지 않은 일 |
| --- | --- |
| 가입 · 로그인 · 사용자별 데이터 나누기 · 하루 분석 한도 | LLM 호출 — AI 서버가 한다 |
| 원본 · 활동 · 경험 · 근거 · 지원 · 문항 · 초안 저장과 조회 · 수정 | 근거 발췌문을 원본과 대조하는 일(글자 단위) — AI 서버가 한다 |
| 분석 작업(자료 분석 · 문항 추천 · 초안 · STAR 정리 · 차곡이 일) 상태와 다시 시도 | 화면 — FE 가 한다 |
| **AI 응답을 받아 보낸 후보와 맞추고, 어긋나면 저장하지 않고 실패로 둔다** | |
| 사용자가 고친 것의 출처 상태 규칙 · 수정 이력 · 외부 AI 변경 기록과 되돌리기 | |
| MCP 서버 — 도구 8개, 연결 주소, 쓰기 켜기 | |

AI 서버가 DB 를 만지지 않고 BE 만 AI 서버를 부르는 구조의 이유는 [ADR-003](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-003-stack-and-ai-boundary.md).

## 분석 작업이 도는 길

자료를 넣으면 BE 가 **한 번에** 원본 저장 · 작업 만들기 · 하루 횟수 차감을 끝내고 바로 응답한다(202). 실제 분석은 뒤에서 돈다.

```mermaid
stateDiagram-v2
    [*] --> QUEUED: 여러 파일을 한 번에 넣음 · 차례 기다림
    [*] --> ANALYZING: 자료 · 문항 넣기
    QUEUED --> ANALYZING: 앞 작업이 끝남
    ANALYZING --> COMPLETED: AI 응답 검증 · 저장
    ANALYZING --> FAILED: AI 오류 · 시간 초과 · 응답이 약속과 다름 · 서버 재시작
    FAILED --> ANALYZING: 다시 시도
```

- 코드: `AnalysisService`(트랜잭션 안: 저장 · 작업 · 횟수) → `ExtractionRunner`(`@Async`, 트랜잭션 밖: AI 호출 · 결과 저장). 문항 추천 · 초안 · 차곡이 일도 같은 모양이다(`MatchRunner` · `DraftRunner` · `ChagokRunner`).
- 사용자는 기다리지 않고 다른 화면을 쓴다. 작업 상태는 새로고침해도 이어진다.
- **일이 안 되면 그 일만 `FAILED`(이유 포함)가 되고 정해진 결과로 채우지 않는다.** 다른 작업과 쌓인 기록은 그대로다.
- 서버가 분석 도중 꺼지면 `InterruptedJobCleaner` 가 시작할 때 멈춰 있던 작업을 실패로 돌려 다시 시도할 수 있게 하고, 남은 차례를 이어 돌린다.
- 하루 한도는 일을 시작하는 그 묶음 안에서 센다(`UsageLimiter`). 동시에 눌러도 한도를 넘지 않는다. 실패한 분석도 센다(LLM 은 이미 불렸을 수 있다).
- 비어 있는 STAR 는 BE 가 알아서 뒤에서 정리한다(`StarSweeper`, 횟수를 세지 않음 — [ADR-019](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-019-star-no-auto-resort.md)).

### AI 응답을 받아 BE 가 확인하는 것

글자 단위 근거 대조와 숫자 대조는 AI 서버가 한다. BE 는 **응답이 요청과 맞는지**를 본다 — 하나라도 어기면 아무것도 저장하지 않고 작업을 `FAILED` 로 둔다.

| 응답 | BE 가 확인하는 것 |
| --- | --- |
| 추출 | `SOURCE_CONFIRMED` 는 근거가 1개 이상이고 **전부 `matched: true`** 일 때만. 확인하지 않은 것을 확인했다고 저장하지 않는다. 역량은 목록 밖 값 · 중복 · 3개 초과를 버린다 |
| 매칭 | 경험 · 근거 · 보완 ID 가 **보낸 후보**에 있는지 · 근거가 그 경험의 것인지 · 추천 · 되묻기 개수(되묻기가 추천한 경험에 붙었는지) · 요구사항 1 ~ 6개 |
| 초안 | 문장 수 · 한 문장인지 · 사실 문장은 근거(`evidence`)가 있고 생각(`OPINION`)은 비었는지 · 「AI 가정」만으로 초안을 만들지 않았는지 · 소제목 길이 · 켠 지침마다 적용 여부 |
| 새 경험 후보 | 후보 수 · 문장이 확정 글에 순서대로 있는지 · 빼야 할 문장이 아닌지 · 활동이 보낸 활동인지 |

## 데이터 — 사실이 층으로 쌓인다

```
SOURCE(원본) ─▶ ACTIVITY(활동) ─▶ EXPERIENCE(경험) ─▶ CITATION(근거 발췌) · SUPPLEMENT(보완 — 되묻기 · STAR 답)
APPLICATION(지원) ─▶ POSTING_LINE(공고 요건 줄) · QUESTION(문항) ─▶ RECOMMENDATION · CLARIFICATION · DRAFT_SENTENCE · ANSWER
MCP_CHANGE(외부 AI 가 바꾼 것) · REVISION(수정 이력)
```

- 출처 상태(원문에서 확인 · 내가 입력 · 확인 필요)와 수행 주체(나 · 팀 · 알 수 없음)가 초안 근거 · 「맞아요」 · 내보내기 · MCP 읽기를 돌린다. 사용자가 고친 본문은 「내가 입력」이 되고 원래 근거는 남는다([ADR-018](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-018-show-only-exceptions.md)).
- 스키마는 Flyway 마이그레이션 48개(`src/main/resources/db/migration/`)로 흐름이 붙을 때마다 자랐다. `ddl-auto=validate` — 코드가 스키마를 바꾸지 않는다.
- 수정 이력은 고치는 요청과 같은 트랜잭션에서 남는다([ADR-013](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-013-revision-history.md)).

## API 지도

경로 · 요청 · 응답 · 오류는 계약이 원본이다(링크). 아래는 영역별 길잡이다. 가입 · 로그인 · 공개 프로필 읽기와, 토큰이 주소에 든 MCP 엔드포인트 말고는 전부 로그인해야 쓴다(401 `UNAUTHENTICATED`).

| 영역 | 경로 | 하는 일 | 계약 |
| --- | --- | --- | --- |
| 로그인 · 한도 | `/api/auth/signup` · `login` · `logout` · `me` | 가입(바로 로그인) · 세션 쿠키 · 오늘 쓴 분석 횟수 / 한도 | `auth-api.md` |
| 자료 · 작업 | `/api/sources` · `/api/sources/batch` · `/api/jobs/{id}` · `.../retry` | 자료 넣기(202, 같은 글자면 409 `SOURCE_DUPLICATE`) · 여러 파일 차례 분석 · 작업 상태 · 다시 시도 | `experience-api.md` |
| 활동 · 경험 | `/api/activities` · `/api/experiences` | 조회 · 고치기 · 직접 추가 · 지우기 · 옮기기 · 합치기 · 경험 찾기(`query` · `competencies` · `year` · `sourceStatus`) · 이어짐 제안 · 같은 활동 제안 · STAR 칸 | `experience-api.md` |
| 지원 · 문항 | `/api/applications` · `/api/questions` | 지원 넣기(문항 1 ~ 10개 또는 공고 요건 줄만) · 문항 추천 작업 · 공고 기준 경험 찾기 · 문항 유형 | `question-api.md` |
| 되묻기 · 보완 | `/api/clarifications/{id}/answer` · `/api/supplements` | 답을 그 경험의 보완으로 저장 · 고치기 · 지우기 | `question-api.md` |
| 초안 · 답 | `/api/questions/{id}/draft` · `.../answer` · `.../answer-draft` · `.../grounding-check` · `.../similar-answers` | 초안 만들기 · 문장 확인 · 답 확정 · 임시 글 · 근거 점검 · 새 경험 후보 · 비슷한 확정 답 | `question-api.md` |
| 작성 지침 | `/api/guidelines` | 지침 정리 · 켜기 · 고치기 | `guideline-api.md` |
| 홈 입구 · 꺼내기 | `/api/entrance/route` · `/api/entrance/parse-application` · `/api/ask` | 입력 가르기 · 지원 나누기(글 · 링크 · 캡처) · 한 줄 질문 | `entrance-api.md` |
| 차곡이 | `/api/chagok/runs` | 일 만들기 · 단계 읽기 · 확인 · 그만두기 | `chagok-api.md` |
| 진행 중인 일 | `/api/work` | 분석 · 초안 · 차곡이 일을 한곳에서 | `work-api.md` |
| 수정 이력 | `/api/revisions` | 전 · 후 스냅샷 조회 · 이 수정 전 값으로 되돌리기 | `revision-api.md` |
| 공개 프로필 · 내보내기 | `/api/public-profile` · `/api/public/profiles/{token}` · `/api/exports` | 고른 경험만 읽기 전용으로 공개(로그인 없이, 꺼진 주소는 404) · Markdown 내보내기 | `share-api.md` · `export-api.md` |
| MCP | `/api/mcp/connection` · `/api/mcp/changes` · `POST /mcp/{token}` | 연결 주소 · 쓰기 켜기 · 외부 AI 변경 기록(맞아요 · 되돌리기) · MCP 엔드포인트 | `mcp-api.md` |

분석은 AI 서버(`AI_BASE_URL`, 계약 `ai-api.md`)를 부른다. 로컬에서 끝까지 돌려 보려면 AI 레포를 8000 포트로 띄운다. AI 서버가 없으면 작업은 `FAILED`(`AI_UNAVAILABLE`)가 된다.

### 로그인 · 하루 한도

- 아이디 · 비밀번호(BCrypt) + 서버 세션 쿠키(`JSESSIONID`, `HttpOnly`, 7일). 세션은 BE 메모리에 있어 BE 를 다시 켜면 다들 다시 로그인한다.
- 분석을 시작하는 곳이 하루 횟수를 쓴다 — 자료 넣기 · 문항 넣기 · 다시 시도는 1회, 지원 넣기는 문항 수만큼(남은 횟수가 모자라면 아무것도 저장하지 않는다). 다 쓰면 429 `DAILY_LIMIT_EXCEEDED` 로 시작하지 않는다. 한도는 `DAILY_ANALYSIS_LIMIT`(기본 20), 한국 시간 자정에 다시 채워진다.
- 입력 크기 한도: 자료 텍스트 50000자 · 문항 5000자. 한도는 세 겹이다 — 사용자별 하루 횟수 · 입력 크기 · LLM 제공사 콘솔의 사용 한도([ADR-004](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-004-deploy.md)).

### 다른 AI 에서 쓰기 — MCP 서버

계약 [`mcp-api.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/contracts/mcp-api.md) · [ADR-008](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-008-mcp-tools-in-be.md) · [ADR-010](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-010-external-ai-write.md) · [ADR-011](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-011-external-ai-features.md)

- **도구 8개, 한 곳에만 정의한다.** 기본(읽기)은 `search_experiences` · `list_activities` · `get_activity`. 연결에서 **쓰기를 켜면** `add_source` · `update_experience` · `create_application` · `get_question` · `answer_clarification` 이 더 열린다. 차곡이도 이 서버의 `tools/list` 를 그대로 쓴다.
- **MCP 라이브러리 없이** Streamable HTTP 다섯 메서드(`initialize` · `notifications/initialized` · `ping` · `tools/list` · `tools/call`)를 상태 없이 JSON 으로 받는 컨트롤러 하나다(`McpController`). 로그인 세션과 상관없이 주소의 토큰으로만 사용자를 정하고, 없거나 끊긴 토큰은 404 다.
- **연결 주소가 곧 비밀번호다.** 32바이트 무작위 토큰을 쓰고 DB 에는 **해시만** 저장한다. 잃어버리면 다시 만든다. 로그에 남기지 않는다.
- **쓰기는 연결마다 켜야 하고 기본은 꺼짐**이다. 주소를 다시 만들면 다시 꺼진다. 하루 쓰기 상한(`MCP_DAILY_WRITE_LIMIT`, 기본 50) · 입력 크기 · 범위는 안내 글이 아니라 **BE 코드가 강제**한다.
- 외부 AI 가 고친 경험은 **`NEEDS_REVIEW`(확인 필요)로 낮추고** 변경 기록(`mcp_change`)을 남긴다. 사용자가 웹에서 「맞아요」를 누르기 전엔 초안 · 근거 점검의 근거로 쓰지 않고, 건별로 되돌릴 수 있다. 자료 넣기는 웹과 같은 추출 · 근거 대조 · 하루 한도를 탄다.
- 주소 앞부분은 `MCP_BASE_URL`(서버에선 공개 API 주소). 로컬 확인: 로그인 뒤 `POST /api/mcp/connection` 으로 주소를 받아 `claude mcp add --transport http woowang <주소>` → 「WebSocket 경험 찾아 줘」.
- claude.ai 커넥터는 Anthropic 서버에서 부르므로 서버에선 Cloudflare Access 가 `/mcp/*` 를 통과시켜야 한다(사람이 설정).

## 기술 선택과 이유

| 정한 것 | 이유 | 버린 안 | 기록 |
| --- | --- | --- | --- |
| Spring Boot · JPA · Flyway · PostgreSQL | 인증 · 데이터 · 작업 상태를 한 서버에서. 스키마는 마이그레이션으로 흐름마다 키운다 | — | [ADR-003](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-003-stack-and-ai-boundary.md) |
| AI 서버와 분리, BE 만 부른다 | 저장 · 사용자 구분 · 호출 횟수를 BE 가 맡아 AI 서버는 JSON 만 보고 답한다 | BE 하나에서 LLM 까지 · AI 서버가 DB 직접 접근 | 같음 |
| 임베딩 없이 경험 전부를 후보로 싣는다(DB 이미지는 pgvector 로 받아 두고 확장은 안 켬) | 한 사람의 경험은 수십 ~ 백여 개라 본문 판정이 단순하고 정확하다 | 처음부터 pgvector · LangGraph | 같음 |
| 서버 한 대 Docker Compose + Cloudflare Tunnel | 팀 크기 · 일정에 맞는 가장 단순한 운영. 밖에는 BE 만 내보낸다 | PaaS 서비스별 배포 · 포트 직접 공개 | [ADR-004](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-004-deploy.md) |
| MCP 서버를 BE 에, 도구는 한 곳 | 토큰 · 사용자 · 한도가 BE 에 있고 안팎이 같은 도구를 쓴다 | 파이썬 MCP 서버를 AI 레포에 · 도구 두 벌 | [ADR-008](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-008-mcp-tools-in-be.md) |
| 외부 AI 쓰기는 바로 반영하고 「확인 필요」로 낮춘다 | 대화를 끊지 않고, 확인 전엔 근거가 아니게 한다 | 앱 안 확인 대기함 · 외부 AI 승인에만 맡김 | [ADR-010](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/decisions/ADR-010-external-ai-write.md) |
| AI 응답이 약속과 다르면 고치지 않고 작업을 실패로 둔다 | 그럴듯하게 손본 결과가 사실처럼 쌓이는 것을 막는다 | 정해진 결과로 채우기 | — |

## 직접 구현 vs 외부 기술

표 전체와 출처는 ETC [`docs/ai-scope.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/ai-scope.md) §1 · §4.

| 직접 구현 | 가져다 쓴 것 |
| --- | --- |
| 저장 구조 · 사용자가 고치면 출처 상태를 「내가 입력」으로 바꾸고 근거는 남기는 규칙 | Spring Boot 4 · Spring Data JPA(Hibernate) · Flyway |
| 분석 작업 상태 · 다시 시도 · 시간 초과를 실패 이유로 남기기 · 서버 재시작 정리 · 차례 | Spring Security Crypto(BCrypt — 비밀번호 해시만) |
| AI 응답 검증(후보 ID · 개수 · 근거 상태) | PostgreSQL 18(`pgvector/pgvector` 이미지) |
| 가입 · 로그인 · 사용자별 하루 한도 · 데이터 나누기(필터 포함 직접) | Testcontainers(테스트) |
| MCP 서버(라이브러리 없이) · 연결 토큰 · 쓰기 범위 · 변경 기록 · 되돌리기 | Docker · Docker Compose · Cloudflare Tunnel · Access |
| 차곡이 일 — 단계 쌓기 · 10분짜리 토큰 · 확인 뒤 실행 | MCP 명세 · Claude(클라이언트) |

## AI 활용에서 BE 의 몫

LLM 은 AI 서버가 부른다. BE 는 **무엇을 보내고 무엇을 받아 저장하는가**를 정한다.

- 보내는 것: 매칭에는 경험 본문 · 근거 · 보완만 싣는다(역량 태그는 싣지 않는다 — 태그가 빠져도 매칭이 무너지지 않게). 외부 AI 를 거쳐 아직 확인 안 된 답은 재료에서 뺀다.
- 받는 것: 위 「AI 응답을 받아 BE 가 확인하는 것」을 통과한 결과만 저장한다.
- AI 기능의 검증 결과와 한계는 [AI 레포 README](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_AI#readme) · ETC [`docs/ai-scope.md`](https://github.com/woowangthon/WOOWANGTHON_TEAM_12_ETC/blob/main/docs/ai-scope.md).

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
- 분석을 끝까지 돌려 보려면 AI 레포를 8000 포트로 띄운다(`AI_BASE_URL` 기본값이 `http://localhost:8000`).

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

## 목 · 샘플 · 테스트 계정

코드의 `MOCK(<id>)` · `SAMPLE(<id>)` 마커는 ETC `docs/ai-scope.md` §3 표와 1:1 이다. 이 레포의 것은 아래 둘이다.

### 테스트 계정 (`SAMPLE(test-account)`)

`TEST_ACCOUNT_PASSWORD` 를 넣고 켜면 테스트 계정이 생긴다(켤 때마다 비밀번호 · 한도를 설정값으로 맞춘다). 데이터는 미리 넣지 않는다 — 진짜 흐름을 그대로 탄다.

| 아이디 | 비밀번호 | 하루 분석 |
| --- | --- | --- |
| `test` | **제출 폼에만** 적는다 — 이 레포는 Public 이라 여기 적으면 누구나 하루 200회 분석을 쓸 수 있다 | 200회 |

비밀번호는 서버 `.env` 와 제출 폼에만 둔다. 팀원에게는 DM 으로 — README · 이슈 · 채널에는 적지 않는다.

`SEED_SAMPLE=true` 면 예시 데이터(`SAMPLE(dev-seed-activities)`)를 이 계정에 넣는다 — 그래서 로컬에서 예시 데이터를 보려면 테스트 계정 비밀번호도 같이 넣는다.

V1 마이그레이션의 사용자 1(`SAMPLE(fixed-user)`)은 아이디 · 비밀번호가 없어 로그인할 수 없다 — 로그인 전 개발 데이터의 주인으로만 남는다.

## 환경변수

변수와 기본값은 [`.env.example`](.env.example) 에 있다: `SEED_SAMPLE` · `CORS_ALLOWED_ORIGINS` · `DAILY_ANALYSIS_LIMIT` · `STAR_AUTO_SORT_ENABLED` · `STAR_AUTO_SORT_MAX_BACKLOG` · `TEST_ACCOUNT_LOGIN_ID` · `TEST_ACCOUNT_PASSWORD` · `TEST_ACCOUNT_DAILY_LIMIT` · `SESSION_COOKIE_SECURE` · `SESSION_COOKIE_SAME_SITE` · `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `AI_BASE_URL` · `AI_TIMEOUT_SECONDS` · `AI_SYNC_TIMEOUT_SECONDS` · `MCP_BASE_URL` · `MCP_INTERNAL_BASE_URL` · `MCP_DAILY_WRITE_LIMIT` · `CHAGOK_TIMEOUT_SECONDS` · `CHAGOK_MAX_TOOL_CALLS` · (서버) `TUNNEL_TOKEN` · `AI_DIR`. `.env` 는 커밋하지 않는다.

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

## 코드 지도

패키지 `com.woowangthon.backend` 아래.

| 패키지 | 하는 일 |
| --- | --- |
| `account` | 가입 · 로그인 · 세션 · 하루 한도(`UsageLimiter`) |
| `source` · `job` | 자료 넣기 · 분석 작업(`AnalysisService` · `ExtractionRunner` · `InterruptedJobCleaner`) |
| `activity` · `experience` | 활동 · 경험 · 근거 · STAR(`StarSweeper`) · 경험 찾기 · 합치기 · 옮기기 |
| `question` | 지원 · 문항 · 추천(`MatchRunner`) · 되묻기 · 초안(`DraftService` · `DraftRunner`) · 답 · 근거 점검 |
| `entrance` | 홈 입력 가르기 · 지원 나누기 · 한 줄 질문 |
| `chagok` | 차곡이 일(`ChagokService` · `ChagokRunner`) — AI `/agent` 스트림을 단계로 쌓기 |
| `mcp` | MCP 서버(`McpController`) · 도구 8개 · 연결 주소 · 변경 기록 |
| `revision` · `guideline` · `share` · `export` · `work` | 수정 이력 · 작성 지침 · 공개 프로필 · Markdown 내보내기 · 진행 중인 일 |
| `ai` | AI 서버 호출(`AiClient` — 실패는 전부 `AiCallException`, 정해진 결과로 채우지 않는다) |
| `seed` | 테스트 계정 · 예시 데이터(`SAMPLE(…)`) |
| `common` | 공통 오류 · 응답 |

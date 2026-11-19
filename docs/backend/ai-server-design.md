# ai/server 설계 — FastAPI 껍데기

> 작성자 : 윤혜정 (이슈 #48)

> `ai/server`(FastAPI 껍데기, job 관리)의 구현 설계다. 담당은 backend다(`CLAUDE.md` 2번 —
> `ai/server`는 예외적으로 backend 담당). `ai/server`를 제외한 `ai/`(분석·예측 로직, 파이프라인
> 연결)는 ai 담당 범위라 여기서 다루지 않는다.
>
> backend ↔ ai 호출 규약 자체는 `docs/backend/ai-contract.md`를 따른다. 이 문서는 그 규약을
> `ai/server` 코드로 어떻게 구현할지를 다룬다.

## 1. 디렉토리 구조

```
ai/server/
├── main.py              # FastAPI 앱 생성, 라우터 등록, startup에서 DB 초기화 + 워커 기동, /health
├── config.py              # 환경변수 로딩: INTERNAL_API_KEY, DB 경로, 동시 실행 수
├── auth.py                # X-Internal-Api-Key 검증 (FastAPI Depends)
├── db.py                  # sqlite3 연결, 스키마 생성(최초 1회)
├── jobs/
│   ├── models.py          # JobStatus enum(QUEUED/RUNNING/SUCCEEDED/FAILED)
│   ├── repository.py      # SQLite CRUD (create/get/update)
│   ├── service.py         # create_job → 즉시 jobId 반환, get_job
│   ├── worker.py          # asyncio.Queue + 워커 N개, 파이프라인 실행·상태 갱신
│   ├── registry.py        # job_type → 파이프라인 함수 매핑, 가짜 구현 기본 등록
│   └── router.py          # POST/GET 엔드포인트
├── requirements.txt
├── Dockerfile              # 로컬 실행 확인용 (compose 등록은 범위 밖, infra 이슈)
└── .env.example
```

## 2. 결정 사항

### 2.1 작업 상태 저장소 — SQLite

루트 `CLAUDE.md` 7번 미결 항목("ai 서비스의 작업 상태 저장 위치")을 `ai/server` 범위에서 SQLite
파일로 정했다.

- `ai/server/jobs.db` 하나의 파일. 재시작해도 상태가 남아, backend가 진행 중이던 작업을 "없는
  작업"(404)으로 오판해 `FAILED` 처리하는 사고(`ai-contract.md` 2.6)를 막는다.
- `docker-compose.yml`/K8s에 새 컨테이너(DB 서버)를 추가할 필요가 없다 — #48 범위("컨테이너
  추가는 별도 infra 이슈")와 맞는다.
- 전제: `ai/server`는 **단일 인스턴스**로만 돈다. SQLite는 여러 프로세스가 같은 파일에
  동시에 쓰는 상황에 적합하지 않다. 3번(확장 가능성)에서 다룬다.

### 2.2 동시 실행 — in-process `asyncio.Queue`

- Redis/Celery 같은 외부 브로커를 쓰지 않는다. 단일 인스턴스에서 워커를 여러 서버로 분배할
  필요가 없고(Redis는 보통 여러 워커/인스턴스가 같은 큐·캐시를 공유해야 할 때 쓴다), 지금은
  공유할 상대가 없다.
- `asyncio.Queue` + 고정 개수(`MAX_CONCURRENT_JOBS`, 기본값은 구현 시 정함) 워커 코루틴으로
  처리한다. 파이프라인 함수는 보통 blocking(Selenium, pdfplumber 등)이라 `asyncio.to_thread`로
  실행해 이벤트 루프를 막지 않는다.
- 동시 실행 개수 제한(#48 체크리스트)은 이 워커 개수가 곧 상한이다.

### 2.3 엔드포인트 — `ai-contract.md` 2.4 그대로

| 메서드/경로 | 응답 | 비고 |
|---|---|---|
| `POST /internal/v1/jobs/document-preparation` | `202` + `{jobId}` | |
| `POST /internal/v1/jobs/analysis` | `202` + `{jobId}` | |
| `POST /internal/v1/jobs/prediction` | `202` + `{jobId}` | |
| `GET /internal/v1/jobs/{jobId}` | `200` / `404` | 없는 jobId만 404 |
| `GET /health` | `200` | 헬스체크 |

- 요청 바디는 이 이슈 범위가 아니다(작업별 입출력은 별도 이슈). `ai-contract.md`의 임시
  필드(`analysisRunId`, `allowedDocumentVersionIds`, `asOfDate` 등)로 placeholder만 두고,
  "확정 전" 주석을 남긴다.

### 2.4 인증 — 공유 비밀키

`X-Internal-Api-Key` 헤더를 env의 `INTERNAL_API_KEY`와 비교하는 FastAPI `Depends` 하나
(`ai-contract.md` 2.10). 불일치하면 401.

### 2.5 오류 규약

없는 jobId만 404(`ai-contract.md` 2.5). 그 외 파이프라인 실행 중 예외는 `FAILED` +
`errorMessage`로 기록하되, 스택트레이스·내부 경로·문서 원문을 담지 않고 500자 이내로 자른다.

### 2.6 가짜 파이프라인

job_type별로 기본 구현 두 가지를 등록해둔다: 항상 성공(지연 포함), 항상 실패. 어떤 걸 쓸지는
설정이나 요청 플래그로 고른다. ai 담당(진섭님)이 실제 파이프라인을 붙이기 전까지 이걸로
전체 흐름(시작 → 상태 → 결과)을 확인한다.

## 3. 지금 안 하는 것 (추측성 설계 금지)

다음은 아직 정해지지 않았거나(`ai-contract.md`의 "열림") 필요하다고 확정되지 않아서, 코드에
미리 끼워 넣지 않는다. 결정되면 그때 추가한다.

| 항목 | 이유 |
|---|---|
| 멱등키 컬럼/로직 | backend가 멱등키를 아직 못 정했다(`ai-contract.md` 2.8). 안 쓸 컬럼을 미리 만들지 않는다 |
| 결과 보관 기간 정리(삭제) 배치 | 정확한 보관 기간이 안 정해졌다(`ai-contract.md` 2.7). MVP는 영구 보관 |
| Redis/외부 큐, 저장소 추상 인터페이스 | 지금 쓰지 않을 구현체를 미리 만들면 죽은 코드가 된다 |

## 4. 확장 가능성 (지금 구현하지 않음, 메모만)

`ai/server`가 여러 인스턴스로 늘어나야 하는 시점(사용자가 늘어 동시 사용이 많아질 때)이 오면
아래를 다시 설계한다. **지금은 코드에 이를 위한 추상화나 설정 분기를 넣지 않는다** — 모듈이
이미 역할별로 나뉘어 있어(`jobs/repository.py`, `jobs/worker.py`) 그때 가서 이 두 모듈만
바꾸면 된다.

| 지금 | 늘어나면 | 바꿀 곳 |
|---|---|---|
| SQLite 파일 하나 | 여러 인스턴스가 공유하는 DB(Postgres 등) | `jobs/repository.py` |
| in-process `asyncio.Queue` | Celery/RQ + Redis 같은 분산 큐 | `jobs/worker.py` |
| 단일 인스턴스 전제 | 로드밸런서 뒤 여러 인스턴스, sticky routing 불필요(공유 상태로 이전) | `main.py`, 배포 설정(infra 영역) |

이 표의 "늘어나면" 쪽은 결정된 사항이 아니라 참고용 방향이다. 실제로 그 시점이 오면 그때
요구사항(동시 사용자 수, 지연 허용치 등)을 보고 다시 정한다.

## 5. 참고

- 호출 규약: `docs/backend/ai-contract.md`
- `Job`(backend 쪽) 전이 규칙: `docs/backend/job.md`
- 폴더 담당: `CLAUDE.md` 2번

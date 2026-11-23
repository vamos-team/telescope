# ai/server 설계 — FastAPI 껍데기

> 작성자 : 윤혜정 (이슈 #48)

> `ai/server`(FastAPI 껍데기, job 관리)의 구현 설계다. 담당은 backend다(`CLAUDE.md` 2번 —
> `ai/server`는 예외적으로 backend 담당). `ai/server`를 제외한 `ai/`(분석·예측 로직, 파이프라인
> 연결)는 ai 담당 범위라 여기서 다루지 않는다.
>
> backend ↔ ai 호출 규약 자체는 `docs/backend/ai-contract.md`를 따른다. 이 문서는 그 규약을
> `ai/server` 코드로 어떻게 구현했는지를 다룬다.

## 1. 디렉토리 구조

```
ai/server/
├── main.py              # create_app(): lifespan(DB 초기화, 재시작 정리, 워커 기동·종료), 오류 핸들러, /health
├── config.py            # 환경변수 설정 (Settings)
├── auth.py              # X-Internal-Api-Key 검증 (FastAPI Depends)
├── db.py                # sqlite3 연결, 스키마 생성
├── jobs/
│   ├── models.py        # JobStatus, JobType, Job
│   ├── pipeline.py      # ai 담당이 따르는 인터페이스: JobContext, PipelineError, PipelineFn
│   ├── registry.py      # job_type → 파이프라인 함수 매핑, 가짜 구현
│   ├── repository.py    # SQLite CRUD, 상태 전이 조건
│   ├── service.py       # jobId 발급, 조회, 재시작 시 미종료 작업 정리
│   ├── worker.py        # asyncio.Queue + 워커 N개, 파이프라인 실행·결과 기록
│   └── router.py        # 요청·응답 스키마, POST/GET 엔드포인트
├── tests/               # pytest
├── pytest.ini
├── requirements.txt
├── Dockerfile           # 로컬 실행 확인용 (compose 등록은 범위 밖, infra 이슈)
└── .env.example
```

## 2. 결정 사항

### 2.1 작업 상태 저장소 — SQLite (ai 담당자와 합의)

루트 `CLAUDE.md` 7번에 미결로 있던 "ai 서비스의 작업 상태 저장 위치"를 ai 담당자와 합의해
SQLite 파일로 정했다.

- 재시작해도 상태가 남아, backend가 진행 중이던 작업을 "없는 작업"(404)으로 오판해 `FAILED`
  처리하는 사고(`ai-contract.md` 2.6)를 막는다. 종료된 작업의 결과도 반복 조회할 수 있다
  (`ai-contract.md` 2.7 멱등 조회).
- `docker-compose.yml`/K8s에 새 컨테이너(DB 서버)를 추가할 필요가 없다. 파이썬 표준 라이브러리
  `sqlite3`만 쓴다.
- 파일 경로는 `DB_PATH` 환경변수로 받고 **기본값을 두지 않는다.** 루트 `data/` 아래(예:
  `data/ai-server/jobs.db`)를 가리키게 한다. `data/`는 git 무시 대상이라 DB 파일이 커밋되지
  않는다. 상위 폴더는 기동 시 만든다.
- WAL 모드로 연다. 연결은 호출마다 새로 연다(쿼리가 작고, 연결을 스레드 간에 공유하지 않아도
  되도록).
- 전제: `ai/server`는 **단일 인스턴스**로만 돈다. SQLite는 여러 프로세스가 같은 파일에
  동시에 쓰는 상황에 적합하지 않다. K8s에 올릴 때는 파일을 둘 영구 볼륨(PVC)이 필요하다
  (infra 이슈). 4번(확장 가능성)에서 다룬다.

```sql
CREATE TABLE jobs (
  job_id        TEXT PRIMARY KEY,   -- uuid4 (36자, backend externalJobId 100자 상한 이내)
  job_type      TEXT NOT NULL,      -- document-preparation / analysis / prediction
  status        TEXT NOT NULL,      -- QUEUED / RUNNING / SUCCEEDED / FAILED
  result_json   TEXT,               -- SUCCEEDED일 때만
  error_message TEXT,               -- FAILED일 때만
  created_at    TEXT NOT NULL,      -- ISO 8601, UTC
  started_at    TEXT,
  finished_at   TEXT
);
```

- 요청 바디는 저장하지 않는다. 재시작 시 다시 실행하지 않으므로(2.7) 보관할 이유가 없다.
- 상태 변경은 허용된 이전 상태에서만 반영된다: `RUNNING`은 `QUEUED`에서만, `SUCCEEDED`/`FAILED`는
  `QUEUED`/`RUNNING`에서만. 종료된 작업은 바뀌지 않는다.

### 2.2 동시 실행 — in-process `asyncio.Queue`

- Redis/Celery 같은 외부 브로커를 쓰지 않는다. 단일 인스턴스에서 워커를 여러 서버로 분배할
  필요가 없고(Redis는 보통 여러 워커/인스턴스가 같은 큐·캐시를 공유해야 할 때 쓴다), 지금은
  공유할 상대가 없다.
- `asyncio.Queue` + `MAX_CONCURRENT_JOBS`(기본 2)개의 워커 코루틴으로 처리한다. 이 워커 수가 곧
  동시 실행 상한이다.
- 파이프라인 함수는 동기 함수로 받고 `asyncio.to_thread`로 실행해 이벤트 루프를 막지 않는다.
  상태 기록(DB 쓰기)은 파이프라인 스레드가 아니라 워커 코루틴이 한다.
- 워커는 작업 하나에서 예상하지 못한 오류(저장소 오류 등)가 나도 로그만 남기고 계속 돈다.
- 큐 길이는 제한하지 않는다. 한도 초과 시 `429`로 응답할지는 `ai-contract.md` 2.8 열림 항목이라
  정해지면 추가한다.

### 2.3 엔드포인트 — `ai-contract.md` 2.4 그대로

| 메서드/경로 | 응답 | 비고 |
|---|---|---|
| `POST /internal/v1/jobs/document-preparation` | `202` + `{jobId}` | |
| `POST /internal/v1/jobs/analysis` | `202` + `{jobId}` | |
| `POST /internal/v1/jobs/prediction` | `202` + `{jobId}` | |
| `GET /internal/v1/jobs/{jobId}` | `200` + `{jobId, status, result, errorMessage}` / `404` | 없는 jobId만 404 |
| `GET /health` | `200` + `{status: "ok"}` | 인증 없음 |

- 시작 요청은 DB에 `QUEUED`로 저장하고 큐에 넣은 뒤 곧바로 `202`를 돌려준다. 파이프라인은
  워커가 뒤에서 실행한다.
- 상태 조회의 `result`는 `SUCCEEDED`일 때만, `errorMessage`는 `FAILED`일 때만 채운다. 나머지는
  `null`이다.
- 요청·응답 필드명은 camelCase다(backend `PythonAiClient` DTO와 1:1). 요청 바디는 이 이슈 범위가
  아니라 backend DTO 초안의 임시 필드를 그대로 쓴다. 작업별 입출력 이슈에서 확정되면 바꾼다.

| 작업 | 임시 요청 필드 |
|---|---|
| 자료 준비 | `documentId`, `documentVersionId`, `fileStorageKey` |
| 분석 | `analysisRunId`, `allowedDocumentVersionIds`(1개 이상), `asOfDate`, `profileSnapshotJson`(선택) |
| 예측 | `analysisRunId`, `asOfDate` |

### 2.4 인증 — 공유 비밀키

`X-Internal-Api-Key` 헤더를 env의 `INTERNAL_API_KEY`와 비교하는 FastAPI `Depends` 하나
(`ai-contract.md` 2.10). `/internal/v1/jobs` 아래 모든 경로에 적용한다. 헤더가 없거나 다르면
`401`. 비교는 `secrets.compare_digest`로 한다.

### 2.5 오류 규약

| 상황 | 응답 |
|---|---|
| 없는 jobId 조회 | `404` (`ai-contract.md` 2.5 — 정확히 이 경우만) |
| 인증 실패 | `401` |
| 요청 검증 실패 | `422`. 응답에는 필드 위치(`loc`), 종류(`type`), 사유(`msg`)만 담는다 |
| 그 밖의 서버 오류 | `500` + 일반 문구. 상세는 서버 로그에만 |

- FastAPI 기본 422 응답은 요청 입력값(`input`)을 그대로 되돌려준다. 문서 본문 등이 섞일 수
  있어 핸들러를 덮어써 입력값을 뺀다.
- 파이프라인 실행 중 실패는 HTTP 오류가 아니라 작업 상태 `FAILED` + `errorMessage`로 기록한다.
  - 파이프라인이 `PipelineError(user_message)`를 던지면 그 메시지를 500자로 잘라 기록한다.
    ai 담당 코드가 "보여도 되는 사유"를 직접 고르는 통로다.
  - 그 밖의 예외(결과가 JSON으로 직렬화되지 않는 경우 포함)는 "작업 처리 중 오류가
    발생했습니다."로 기록하고, 스택트레이스는 서버 로그에만 남긴다. 예외 문자열을 잘라서
    내보내지 않는 이유는 내부 경로·문서 원문이 섞일 수 있어서다.

### 2.6 가짜 파이프라인

실제 파이프라인이 붙기 전까지 모든 작업 종류에 가짜 구현을 등록한다. 동작은 환경변수로 고른다.

- `FAKE_PIPELINE_MODE`: `success`(기본, `{"fake": true}` 반환) 또는 `fail`(`PipelineError` 발생)
- `FAKE_PIPELINE_DELAY_SECONDS`: 끝나기 전 지연(기본 3초)

요청 플래그로 고르는 방식은 쓰지 않는다. 계약에 없는 필드가 API에 남기 때문이다. 테스트는
`create_app(settings, registry)`로 원하는 함수를 직접 등록한다.

### 2.7 재시작 처리 — 미종료 작업은 `FAILED`로 확정

서버가 내려가면 메모리 큐와 실행 중이던 스레드가 사라진다. 그런데 DB에는 `QUEUED`/`RUNNING`이
남아, 그대로 두면 backend가 끝나지 않는 작업을 영원히 폴링한다(`ai-contract.md` 2.5의
"무한 재시도"와 같은 증상).

- 기동 시(lifespan) 미종료 작업을 모두 `FAILED` + "서버 재시작으로 작업이 중단되었습니다."로
  바꾼다. backend는 다음 폴링에서 이를 받아 `Job`을 실패로 기록하고, 사용자가 재요청한다.
- 다시 큐에 넣지 않는다. 같은 작업이 두 번 실행될 수 있고, 멱등성 설계(`ai-contract.md` 2.8)가
  정해지지 않았다.
- 종료된 작업(`SUCCEEDED`/`FAILED`)은 그대로 남아 계속 조회된다.

### 2.8 기준 시점·허용 문서 고정 주입

허용 문서 범위와 기준 시점은 서버가 강제한다(루트 `CLAUDE.md` 4번). 파이프라인이 이 값을
바꾸거나 넓히지 못하게 다음 구조로 넘긴다.

```python
# jobs/pipeline.py
@dataclass(frozen=True)
class JobContext:
    job_id: str
    as_of_date: date | None                              # 자료 준비는 None
    allowed_document_version_ids: frozenset[str] | None  # None = 이 작업 종류에 목록 없음(전체 허용 아님)

PipelineFn = Callable[[JobContext, dict], dict]          # (ctx, payload) -> result
```

- 라우터가 요청에서 `asOfDate`, `allowedDocumentVersionIds`를 꺼내 `JobContext`를 만든다.
  나머지 필드만 `payload`로 넘긴다 — 파이프라인은 이 두 값을 `ctx`로만 받는다.
- `JobContext`는 frozen dataclass, 허용 목록은 `frozenset`이라 수정할 수 없다.
- 분석 요청에서 허용 목록이 비었거나 공백 ID가 있거나 기준 시점이 없으면 `422`로 거절한다.
  빈 목록을 "전체 허용"으로 해석하는 폴백은 없다.
- 예측 요청에는 아직 허용 목록이 없다(`ai-contract.md` 2.9 간극). 계약에 추가되면 라우터에서
  `ctx`로 넘기기만 하면 된다.
- MCP 클라이언트도 이후 `ctx`의 값으로 범위가 묶인 형태로 파이프라인에 넘길 예정이다. MCP 서버가
  범위 밖이라 지금은 자리만 있다.

### 2.9 설정 (환경변수)

| 변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `INTERNAL_API_KEY` | ○ | — | backend와 공유하는 비밀키 |
| `DB_PATH` | ○ | — | SQLite 파일 경로 (`data/` 아래 권장) |
| `MAX_CONCURRENT_JOBS` | | 2 | 동시 실행 상한 (1 이상) |
| `FAKE_PIPELINE_MODE` | | `success` | `success` / `fail` |
| `FAKE_PIPELINE_DELAY_SECONDS` | | 3.0 | 가짜 파이프라인 지연 |
| `LOG_LEVEL` | | `INFO` | |

LLM 키, MCP 주소, 파일 저장 경로는 그 값을 쓰는 파이프라인 코드가 붙을 때 추가한다.

### 2.10 실행과 테스트

```bash
cd ai/server
pip install -r requirements.txt
INTERNAL_API_KEY=... DB_PATH=../../data/ai-server/jobs.db uvicorn main:app --port 8000
pytest
```

테스트 범위: 상태 전이, 즉시 반환, 없는 작업 404, 인증 401, `PipelineError` 메시지 전달, 일반
예외 숨김, 500자 자르기, 직렬화 불가 결과, 동시 실행 상한, `ctx` 고정·payload 분리, 허용
목록·기준 시점 누락 422, 422 응답의 입력값 제거, 작업 종류별 시작, 재시작 후 정리.

## 3. 지금 안 하는 것 (추측성 설계 금지)

다음은 아직 정해지지 않았거나(`ai-contract.md`의 "열림") 필요하다고 확정되지 않아서, 코드에
미리 끼워 넣지 않는다. 결정되면 그때 추가한다.

| 항목 | 이유 |
|---|---|
| 멱등키 컬럼/로직 | backend가 멱등키를 아직 못 정했다(`ai-contract.md` 2.8). 안 쓸 컬럼을 미리 만들지 않는다 |
| 결과 보관 기간 정리(삭제) 배치 | 정확한 보관 기간이 안 정해졌다(`ai-contract.md` 2.7). MVP는 영구 보관 |
| 큐 길이 제한, `429` 응답 | 한도 초과 시 응답 방식이 안 정해졌다(`ai-contract.md` 2.8) |
| 재시작 후 작업 재실행 | 중복 실행 위험. 멱등성이 정해진 뒤 다시 검토(2.7) |
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

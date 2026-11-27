# ai/server 설계 — FastAPI 껍데기

> 작성자 : 윤혜정 (이슈 #48)

> `ai/server`(FastAPI 껍데기, job 관리)의 구현 설계다. 담당은 backend다(`AGENTS.md` 2번 —
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
│   ├── registry.py      # job_type → 파이프라인 함수 매핑, 가짜/실제 등록(PIPELINE_MODE)
│   ├── files.py         # 자료 준비 원본을 로컬 경로로 준비(S3 내려받기 또는 로컬 폴더)
│   ├── repository.py    # SQLite CRUD, 상태 전이 조건
│   ├── service.py       # jobId 발급, 조회, 재시작 시 미종료 작업 정리
│   ├── worker.py        # asyncio.Queue + 워커 N개, 파이프라인 실행·결과 기록
│   └── router.py        # 요청·응답 스키마, POST/GET 엔드포인트
├── tests/               # pytest (sample_entrypoint.py: 실제 모드 테스트용 등록 함수)
├── pytest.ini
├── requirements.txt
├── Dockerfile           # 빌드 위치는 저장소 루트(pipelines/, ai/ 포함). compose·K8s 등록은 infra 이슈
├── Dockerfile.dockerignore  # 이 Dockerfile 전용 무시 목록
└── .env.example
```

## 2. 결정 사항

### 2.1 작업 상태 저장소 — SQLite (ai 담당자와 합의)

루트 `AGENTS.md` 7번에 미결로 있던 "ai 서비스의 작업 상태 저장 위치"를 ai 담당자와 합의해
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
  상태 기록(DB 쓰기)은 파이프라인 스레드가 아니라 워커 코루틴이 하고, 저장소 호출도
  `asyncio.to_thread`로 실행한다.
- 시작 요청 핸들러는 동기 함수라 스레드 풀에서 돈다. `asyncio.Queue`는 스레드 안전하지 않으므로
  워커 시작 시 이벤트 루프를 보관하고 `loop.call_soon_threadsafe`로 큐 삽입을 넘긴다.
- 워커는 작업 하나에서 예상하지 못한 오류가 나도 로그만 남기고 계속 돈다.

**저장소 오류** — 실행 전 실패와 실행 후 저장 실패를 구분한다.

| 시점 | 처리 |
|---|---|
| 실행 전(`mark_running`) | `STORE_RETRY_ATTEMPTS`번 재시도(지수 대기). 끝내 실패하면 파이프라인을 실행하지 않고 `FAILED`("작업을 시작하지 못했습니다.") |
| 실행 후 최종 저장 | 같은 횟수만큼 재시도. 끝내 실패하면 결과(또는 실패 사유)를 메모리에 보관하고, `PENDING_RETRY_INTERVAL_SECONDS`마다 저장만 다시 시도한다. **파이프라인은 다시 실행하지 않는다** |

보관 중인 동안 DB에는 `RUNNING`으로 보인다. 저장하지 못한 채 종료되면 재시작 정리(2.7)에서
`FAILED`가 된다.

**정상 종료** — 종료가 시작되면(lifespan shutdown) 다음 순서로 처리한다.

1. 새 작업을 받지 않는다. 시작 요청은 `503`.
2. 큐에서 기다리던 작업은 시작하지 않는다(DB에 `QUEUED`로 남아 재시작 시 `FAILED`).
3. 실행 중인 작업이 끝나고 최종 상태를 저장할 때까지 `SHUTDOWN_TIMEOUT_SECONDS`(기본 20초)
   기다린다.
4. 넘기면 그 작업을 `FAILED`("서버 종료로 작업이 중단되었습니다.")로 기록한다. 실행 스레드는
   취소로 멈추지 않으므로, 프로세스가 그 스레드를 기다릴 수 있다. 강제 종료(K8s grace period 초과
   등)로 기록하지 못한 작업은 재시작 정리(2.7)가 맡는다.
5. 보관 중인 최종 저장을 한 번 더 시도한다.

K8s에 올릴 때는 `terminationGracePeriodSeconds`를 `SHUTDOWN_TIMEOUT_SECONDS`보다 길게 잡는다
(infra 이슈).
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
| 종료 중 시작 요청 | `503` |
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

### 2.6.1 실제 파이프라인 등록 (#86)

`PIPELINE_MODE=real`이면 `PIPELINE_ENTRYPOINT`("모듈경로:함수명")가 가리키는 ai 담당 등록 함수를 불러온다.

```python
# ai 담당 코드 (예: PIPELINE_ENTRYPOINT=ai.pipelines:register)
def register(registry: PipelineRegistry) -> None:
    registry.register(JobType.DOCUMENT_PREPARATION, prepare)   # 구현한 작업 종류만
```

- **등록하지 않은 작업 종류는 시작 요청을 `501`로 거절한다**("아직 지원하지 않는 작업입니다.").
  작업을 만들지 않으며, backend는 시작 실패를 `markFailed`로 끝낸다(`ai-contract.md` 2.5).
  가짜 구현을 섞어 쓰지 않는 이유: 배포에서 가짜 결과가 실제 결과처럼 저장될 수 있다.
  분석·예측 파이프라인(#66 등)이 등록 함수에 추가되면 거절이 자연히 풀린다.
- **자료 준비 원본은 서버가 준비한다.** 등록된 자료 준비 함수를 감싸, `fileStorageKey`의 원본을 로컬
  경로로 만든 뒤 payload의 `file_path`로 넘긴다. ai 코드는 저장소 방식을 모르고 경로만 읽는다.
  - `S3_BUCKET`이 있으면 S3에서 임시 폴더로 내려받고(확장자 유지), 작업이 끝나면(성공·실패 모두) 지운다.
    자격증명은 boto3 기본 체인(배포는 IRSA)이다.
  - 없으면 `FILE_STORAGE_DIR`(backend 로컬 저장소와 같은 폴더)에서 그 경로를 그대로 쓴다.
  - 키가 폴더를 벗어나거나(`../`, 절대 경로, 하위 폴더) 원본이 없으면 "원본 파일을 찾을 수 없습니다.",
    그 밖의 S3 오류는 "원본 파일을 읽지 못했습니다."로 실패한다. 키·버킷은 문구에 넣지 않는다.
- 잘못된 설정은 기동 시점에 막는다: 자료 준비가 등록됐는데 원본 위치(S3·로컬)가 없음, 버킷은 있는데 리전이 없음.
  (#135에서 바뀜: real 모드에서 등록 함수는 선택이다. 없으면 자료 준비·분석·예측은 `501`이고, 데이터 적재는
  `DATASYNC_DATA_DIR`이 있으면 ai/server가 직접 붙인다. 이유는 `docs/backend/datasync.md` S9)
- 이미지에는 `pipelines/`, `ai/` 코드만 넣고 **pipelines 의존성(docling, torch 등)과 모델 파일은 아직
  설치하지 않는다.** 실제 자료 준비 파이프라인(ai 담당, 별도 이슈)이 생길 때 ai 담당과 함께 정한다
  (모델을 이미지에 넣을지 기동 시 받을지, 그에 맞춘 `MAX_CONCURRENT_JOBS` 기본값). 그 전까지 이미지는 가짜 모드로만 쓴다.

### 2.6.2 결정 기록: 실제 파이프라인 등록 (#86, PR #110)

출발점: ai/server는 가짜 파이프라인만 등록했고, 이미지에는 `ai/server` 폴더만 들어갔다. 붙일 실제 자료 준비 함수(ai 담당)는 아직 없었다.

#### A1. ai 코드는 설정이 가리키는 등록 함수로 붙인다

| 후보 | 판단 |
|---|---|
| **(a) `PIPELINE_ENTRYPOINT="모듈경로:함수명"`의 `register(registry)`를 불러온다** (선택) | ai 폴더를 건드리지 않고 호출 규약만 정한다. ai 담당이 함수 하나만 만들면 붙는다 |
| (b) ai/server가 ai 모듈을 직접 import해 등록 | ai/server(backend 담당)가 ai 코드 구조를 알아야 하고, ai가 바뀔 때마다 ai/server도 고쳐야 한다 |

- `PIPELINE_MODE=fake`(기본)와 `real`로 나눠, 로컬 개발과 화면 연동은 지금처럼 가짜로 한다.

#### A2. 실제 모드에서 등록되지 않은 작업 종류는 `501`로 시작을 거절한다

| 후보 | 장점 | 단점 |
|---|---|---|
| **(a) 시작 거절 (`501`, 작업을 만들지 않음)** (선택) | 배포에서 가짜 결과가 실제 결과처럼 저장될 위험이 없다. backend는 규약대로 시작 실패(`markFailed`)로 끝낸다 | 분석·예측 화면은 파이프라인이 붙을 때까지 실패로 보인다 |
| (b) 가짜 유지 | 화면 흐름이 이어진다 | 배포에서 `{"fake": true}` 같은 결과가 진짜처럼 쌓인다 |

- 사용자 결정: (a)로 하되 TODO 주석을 남긴다(`jobs/registry.py`, `jobs/service.py`). 분석·예측이 등록 함수에 붙으면 거절은 자연히 풀린다.
- 응답 코드로 `501 Not Implemented`를 고른 이유: "서버가 이 기능을 아직 지원하지 않는다"는 뜻이 정확하고, `404`(모르는 jobId, 2.5에서 따로 쓰임)나 `422`(요청 형식 오류)와 섞이지 않는다.

#### A3. S3 원본은 ai/server 껍데기가 받아 로컬 경로로 넘긴다

| 후보 | 장점 | 단점 |
|---|---|---|
| **(a) 껍데기가 받아 payload의 `file_path`로 넘김** (선택) | ai 코드는 S3·로컬 구분 없이 경로만 읽는다. 저장소가 바뀌어도 ai 코드는 그대로다. 임시 파일 정리를 한 곳에서 한다 | ai/server에 boto3 의존성이 생긴다 |
| (b) `fileStorageKey`를 그대로 넘기고 ai 코드가 S3에서 직접 읽음 | 껍데기가 단순하다 | ai 코드가 저장소 방식과 자격증명을 알아야 한다. 로컬 개발에서는 다른 경로를 따로 짜야 한다 |

- `file_path`를 `JobContext`가 아니라 payload에 넣은 이유: `JobContext`는 기준 시점·허용 문서처럼 "서버가 고정해 바꿀 수 없는 값"을 담는 계약이다. 파일 경로는 자료 준비에만 있는 값이라 계약을 넓히지 않았다. 원래 payload는 바꾸지 않고 복사본에 더한다.
- 임시 파일은 원본 확장자를 유지한다. 파서(`pipelines.parsers.parse`)가 확장자로 형식을 고르기 때문이다.
- 키 검증: `../`, 절대 경로, 하위 폴더가 섞인 키는 "원본 파일을 찾을 수 없습니다."로 거절한다(backend 키 형식은 파일 이름 하나).
- 사용자 문구에 키·버킷을 넣지 않는다. 없는 키는 "원본 파일을 찾을 수 없습니다.", 그 밖의 S3 오류(권한 등)는 "원본 파일을 읽지 못했습니다."로 나눈다.

#### A4. pipelines 의존성과 모델 파일은 아직 설치하지 않는다

| 후보 | 판단 |
|---|---|
| **(a) 실제 파이프라인이 생길 때까지 보류** (선택) | 쓰지 않는 수 GB짜리 이미지(docling, torch, sentence-transformers)를 미리 만들지 않는다. 모델 처리 방식과 메모리 한도는 실제 함수가 있어야 잴 수 있다 |
| (b) 지금 설치, 모델은 기동 시 다운로드 | 이미지가 커지고 파드를 재시작할 때마다 모델을 다시 받는다 |
| (c) 지금 설치, 모델도 이미지에 포함 | 기동이 빠르지만 이미지가 가장 크고, 빌드 때 `HF_TOKEN`이 필요할 수 있다 |

- 이 항목은 #109로 넘겼다. 그 전까지 이미지는 가짜 모드로만 쓴다. 이미지 크기는 354MB였다.

#### A5. boto3는 1.44.0으로 고정하고, 테스트는 botocore `Stubber`로 한다

| 후보 (2026-11-26 기준) | 배포일 | 판단 |
|---|---|---|
| 1.44.12 | 11-25 | 최신, 배포 직후 |
| **1.44.0** (선택) | 11-10 | 배포 2주가 지났다. backend AWS SDK(2.58.0, 11-11)와 같은 기준 |
| 1.43.124 | 11-04 | 1.43 계열 마지막 |

- `botocore`는 boto3가 허용하는 1.44.x 범위에서 받아진다(로컬 설치 1.44.12).
- 테스트에는 botocore에 들어 있는 `Stubber`를 쓴다. moto 같은 패키지를 더하지 않는다. `download_file`은 내부에서 여러 요청을 보내 가짜 응답을 만들기 번거로워, `get_object` 응답 스트림을 직접 파일에 쓰는 방식으로 구현했다.

#### A6. 이미지는 저장소 루트에서 빌드하고, 무시 목록은 이 Dockerfile 전용으로 둔다

- `pipelines/`와 `ai/`를 함께 넣어야 하므로 빌드 위치를 저장소 루트로 바꿨다: `docker build -f ai/server/Dockerfile .`, `PYTHONPATH=/app`(#135에서 `/app:/app/pipelines`로. 수집기·적재기가 pipelines 폴더 기준으로 서로를 불러서, `datasync.md` S11).
- 무시 목록은 루트 `.dockerignore` 대신 `ai/server/Dockerfile.dockerignore`에 뒀다(BuildKit은 Dockerfile 이름 옆의 무시 목록을 먼저 쓴다). 루트 파일은 지시가 있을 때만 고치고(루트 `AGENTS.md` 2번), 루트 무시 목록은 다른 빌드에도 영향을 줄 수 있다.
- 무시 목록은 "전부 제외 후 `ai/`, `pipelines/`만 포함"으로 썼다. 이미지에 `tests/`, `.env`, `__pycache__`, `Icon` 파일이 들어가지 않는 것을 확인했다.

#### A7. `FILE_STORAGE_DIR`에는 기본값을 두지 않는다

- 로컬 원본 폴더는 backend의 `FILE_STORAGE_DIR`과 같은 곳이어야 한다. backend 기본값 `./data/files`는 backend 실행 폴더 기준이라, ai/server(`ai/server`에서 실행)에 같은 상대 경로를 두면 다른 폴더를 가리킨다.
- 그래서 기본값 없이, 실제 모드인데 `S3_BUCKET`도 `FILE_STORAGE_DIR`도 없으면 기동을 멈춘다. 잘못된 설정 조합(실제 모드인데 등록 함수 없음, 버킷은 있는데 리전 없음)도 같은 방식으로 막는다.

#### A8. `.env`에서 서버가 선언하지 않은 키는 무시한다 (CodeRabbit 지적 반영)

- 문제: 같은 `.env`에 ai 코드가 직접 읽는 키(`OPENAI_API_KEY` 등)를 채우면, pydantic-settings 기본값(`extra="forbid"`) 때문에 서버 기동이 실패했다(로컬에서 재현). 같은 키를 프로세스 환경변수로 주면 문제가 없었다.

| 후보 | 판단 |
|---|---|
| **(a) `extra="ignore"`** (선택) | 같은 `.env`를 공유해도 기동한다. 선언한 서버 키의 검증은 그대로다 |
| (b) 파이프라인 키를 서버 설정 필드로 선언 | 서버가 쓰지 않는 비밀 값을 서버 설정 객체가 들고 있게 된다 |
| (c) `.env`를 서버용·파이프라인용으로 나눔 | 로컬 개발에서 파일이 둘이 되어 실수하기 쉽다 |

- 대가: 서버 키 이름을 틀리게 쓰면(예: `MAX_CONCURENT_JOBS`) 오류 없이 무시되고 기본값이 쓰인다. 이름은 `.env.example`을 따른다고 주석에 적었다.
- 같은 리뷰에서 S3 응답 스트림을 실패 경로에서도 닫도록(`contextlib.closing`) 고쳤다. 쓰기 실패를 흉내 낸 테스트가 수정 전 코드에서 실패하는 것을 확인했다.

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

허용 문서 범위와 기준 시점은 서버가 강제한다(루트 `AGENTS.md` 4번). 파이프라인이 이 값을
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
| `PIPELINE_MODE` | | `fake` | `fake`(모든 작업 가짜, 데이터 적재는 늘 실패) / `real`(등록된 작업만, 나머지 `501`) |
| `PIPELINE_ENTRYPOINT` | | — | ai 담당 등록 함수 `모듈경로:함수명`. 없으면 ai 파이프라인을 붙이지 않는다 |
| `DATASYNC_DATA_DIR` | | — | 재무 데이터 적재 작업 폴더(#135). real 모드에서 값이 있으면 적재를 받는다 |
| `OPENDART_API_KEY` | 적재 시 ○ | — | 수집기가 프로세스 환경변수(또는 저장소 루트 `.env`)에서 직접 읽는다. `ai/server/.env`로는 전달되지 않는다. 배포는 Secret(`datasync.md` 3장) |
| `S3_BUCKET` | | — | 있으면 자료 준비 원본을 S3에서 받는다 |
| `AWS_REGION` | `S3_BUCKET`이면 ○ | — | S3 리전 |
| `FILE_STORAGE_DIR` | real이고 S3가 없으면 ○ | — | 로컬 원본 폴더. backend의 `FILE_STORAGE_DIR`과 같은 곳 |
| `FAKE_PIPELINE_MODE` | | `success` | `success` / `fail` |
| `FAKE_PIPELINE_DELAY_SECONDS` | | 3.0 | 가짜 파이프라인 지연 |
| `SHUTDOWN_TIMEOUT_SECONDS` | | 20.0 | 종료 시 실행 중 작업을 기다리는 최대 시간 |
| `STORE_RETRY_ATTEMPTS` | | 3 | 저장소 호출 시도 횟수(첫 시도 포함) |
| `STORE_RETRY_DELAY_SECONDS` | | 0.5 | 재시도 첫 대기 시간(지수 증가) |
| `PENDING_RETRY_INTERVAL_SECONDS` | | 5.0 | 최종 저장 실패분을 다시 시도하는 주기 |
| `LOG_LEVEL` | | `INFO` | |

`.env`에서 위 표에 없는 키(ai 파이프라인용 `OPENAI_API_KEY` 등)는 서버 설정이 무시한다. 같은 `.env`를 공유해도 기동이 실패하지 않는다. 표의 키는 그대로 검증한다.

LLM 키(`OPENAI_API_KEY`), 임베딩 모델(`PARSER_EMBED_MODEL`), `HF_TOKEN`은 ai 코드가 직접 읽는다(`.env.example`에 이름만).
ChromaDB·MCP 주소는 그 값을 쓰는 파이프라인 코드가 붙을 때 추가한다.

### 2.10 실행과 테스트

```bash
cd ai/server
pip install -r requirements.txt
INTERNAL_API_KEY=... DB_PATH=../../data/ai-server/jobs.db uvicorn main:app --port 8000
pytest
```

이미지는 저장소 루트에서 빌드한다(`pipelines/`, `ai/`를 함께 넣는다).

```bash
# 저장소 루트에서
docker build -f ai/server/Dockerfile -t telescope-ai-server .
```

테스트 범위: 상태 전이, 즉시 반환, 없는 작업 404, 인증 401, `PipelineError` 메시지 전달, 일반
예외 숨김, 500자 자르기, 직렬화 불가 결과, 동시 실행 상한, `ctx` 고정·payload 분리, 허용
목록·기준 시점 누락 422, 422 응답의 입력값 제거, 작업 종류별 시작, 재시작 후 정리, 정상 종료 시
실행 중 작업 완료 저장, 종료 대기 초과 시 FAILED, 종료 후 제출 거부, 다른 스레드에서의 제출(디버그
모드 루프), 최종 저장 실패 시 즉시·백그라운드 재시도(파이프라인 1회), 실행 전 저장 실패 시 FAILED.

동시 실행처럼 타이밍이 걸린 테스트는 고정 지연 대신 `threading.Event`로 진입·해제를 제어한다.

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
- backend 파일 저장소(키 형식, S3 구현): `docs/backend/file-storage.md`
- `Job`(backend 쪽) 전이 규칙: `docs/backend/job.md`
- 폴더 담당: `AGENTS.md` 2번

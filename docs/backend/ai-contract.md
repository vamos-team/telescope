# backend ↔ ai 호출 공통 규약

> 작성자 : 윤혜정 (이슈 #47)

> **상태: 초안.** backend 쪽에서 기존 코드(`job` 도메인, `PythonAiClient` 가칭 초안)와
> `docs/erd/entities.md`를 바탕으로 정리한 제안이다. ai 담당자의 확인을 거쳐야 이슈 #47의
> 체크리스트를 완료 처리할 수 있다. 항목별로 "제안"과 "열림(합의 필요)"을 구분해 표시했다.
>
> 이 문서는 **공통 규약**만 다룬다. 작업별(자료 준비/분석/예측/평가/재무 데이터 적재) 구체적인
> 요청·응답 필드는 각 작업의 입출력 이슈에서 ai 담당이 정한다. 여기서 쓰는 요청 예시는
> 공통 규약을 설명하기 위한 것이고, 필드 확정은 해당 이슈를 따른다.
>
> 전제: backend(HTTP 껍데기, job 관리)와 ai(구체적 API, 파이프라인)는 역할이 다르다.
> 분석·예측·ML 로직은 backend에 두지 않는다(`backend/CLAUDE.md` 3번). ai는 결과를 backend가
> 저장할 수 있는 형태로 돌려주기만 한다. 결과 저장은 backend만 한다(루트 `CLAUDE.md` 3번).

## 1. 전체 그림

```
backend                                   ai
  │                                        │
  │  POST /internal/v1/jobs/{jobType}      │
  │ ─────────────────────────────────────▶ │  (허용 문서·기준 시점은 backend가 확정해 전달)
  │ ◀───────────────────────────────────── │
  │   202 { jobId }                        │  jobId는 ai가 발급 (string)
  │                                        │
  │  Job.create, externalJobId = jobId     │  ai는 실제 작업을 백그라운드로 시작
  │  (RUNNING)                             │
  │                                        │
  │  GET /internal/v1/jobs/{jobId}  ───────▶│  (클라이언트의 폴링마다, 종료 전까지 반복)
  │ ◀───────────────────────────────────── │
  │   200 { jobId, status, result,         │
  │         errorMessage }                 │
  │                                        │
  │  status == SUCCEEDED/FAILED            │
  │  → Job 갱신 + 대상 도메인이 result 저장 │
```

- backend가 노출하는 `GET /api/v1/jobs/{id}`(클라이언트용, `docs/backend/job.md`)와 이 문서의
  `GET /internal/v1/jobs/{jobId}`(ai 호출용)는 **다른 API**다. 전자는 `Job.id`(숫자, PK)를 쓰고,
  후자는 ai가 발급한 `externalJobId`(문자열)를 쓴다. 섞어 쓰지 않는다.
- ai 쪽 엔드포인트의 기반 경로(`/internal/v1`)는 제안이다. backend의 공개 API(`/api/v1`)와
  헷갈리지 않도록 "내부 호출"임을 이름에 드러내자는 의도이며, ai 담당자가 FastAPI 껍데기를
  만들 때 원하는 경로로 바꿔도 된다. **열림.**

## 2. 결정 사항

### 2.1 jobId 형식과 발급 방식 — 제안

- jobId는 ai가 발급하는 **문자열**이다. 형식은 ai가 정하되, UUID v4를 권장한다
  (`PythonAiClientStub`이 이미 `UUID.randomUUID().toString()`을 쓴다).
- backend는 이 값을 그대로 `Job.externalJobId`(문자열 컬럼)에 저장한다. 의미를 해석하지 않고
  "같다/다르다"만 비교하므로, ai 쪽 형식이 바뀌어도 backend 코드는 영향받지 않는다.
- 클라이언트에게 노출되는 jobId는 이것과 **다르다**(1번 그림 참고). backend가 자체적으로
  발급하는 `Job.id`(bigint, PK)가 클라이언트용 jobId다. ai의 jobId는 backend 내부에서만 쓴다.

### 2.2 작업 상태 값과 전이 — 제안

- ai는 `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` 네 값만 돌려준다. 이미
  `AiJobStatus`(backend 쪽 변환용 enum)와 일치한다. 새 상태 값을 추가하지 않는다(필요하면
  2.3의 `progress`로 표현).
- 상태는 전이 규칙(`QUEUED → RUNNING → SUCCEEDED`, 또는 언제든 `FAILED`)을 따른다고
  **가정하지 않는다.** backend의 `Job.syncFrom`은 같은 상태 반복, 단계 건너뜀(`QUEUED`에서
  바로 `SUCCEEDED`)을 정상으로 보고 흡수한다(`docs/backend/job.md` 3.2). ai는 폴링 타이밍에
  따라 중간 상태를 못 보여줄 수 있다는 뜻이며, 이를 피하려고 애쓰지 않아도 된다.
- `FAILED`일 때는 `errorMessage`를 반드시 채운다. 2.5의 범위 규칙을 따른다.
- `SUCCEEDED`일 때는 `result`를 반드시 채운다. `result`의 모양은 작업별 이슈에서 정한다
  (이 문서에서는 다루지 않는다).

### 2.3 단계 표시(`step`, `progress`) — 제안: MVP에서 제외

- MVP에서는 두지 않는다. 클라이언트는 `QUEUED/RUNNING/SUCCEEDED/FAILED`만 보고 폴링하며,
  `job.md`도 진행률 표시를 요구하지 않는다. 필드를 추가하면 backend의 `Job` 엔티티·테이블에도
  컬럼이 늘어 두 쪽을 함께 바꿔야 한다.
- 나중에 필요해지면(사용자 체감 대기 시간이 길어지는 작업이 생기면) `progress`(0~100 정수)
  하나만 추가하는 쪽을 권장한다. `step`(문자열 단계명)은 ai 파이프라인 내부 구조를 노출하는
  것이라 API 계약에는 넣지 않는다.

### 2.4 호출 형태 (경로, 방식, 응답 코드) — 제안, ai 담당자 최종 확인 필요

| 호출 | 메서드/경로 | 성공 응답 | 용도 |
|---|---|---|---|
| 자료 준비 시작 | `POST /internal/v1/jobs/document-preparation` | `202` + `{ "jobId": "..." }` | `PythonAiClient.startDocumentPreparation` |
| 분석 시작 | `POST /internal/v1/jobs/analysis` | `202` + `{ "jobId": "..." }` | `PythonAiClient.startAnalysis` |
| 예측 시작 | `POST /internal/v1/jobs/prediction` | `202` + `{ "jobId": "..." }` | `PythonAiClient.startPrediction` |
| 상태 조회 | `GET /internal/v1/jobs/{jobId}` | `200` + `{ "jobId", "status", "result", "errorMessage" }` | `PythonAiClient.getJobStatus`. 폴링마다 반복 호출됨 |

- 작업 종류별로 경로를 나눈 이유: 각 작업의 요청 바디(`AnalysisRequest`, `PredictionRequest`,
  `DocumentPreparationRequest`)가 서로 다르다. 하나의 경로(`POST /jobs`)에 `jobType`을 바디로
  받는 방식도 가능하지만, 요청 스키마가 작업마다 달라 OpenAPI 문서가 더 단순해지는 쪽(경로
  분리)을 제안한다. **열림 — ai 담당자가 FastAPI 라우팅 선호를 밝히면 그대로 맞춘다.**
- 응답 바디는 backend의 `ApiResponse`(`data`/`error` 포장) 형식을 **따르지 않는다.** 그 포장은
  backend가 "클라이언트"에게 보내는 응답 전용 규약이고, ai는 backend의 내부 호출 상대일
  뿐이라 `PythonAiClient`의 DTO(`JobStartResponse`, `JobStatusResponse`) 필드와 1:1로 맞는
  평평한 JSON이면 된다.
- 재무 데이터 적재(`DataSyncRun`)와 예측 평가(`PredictionEvaluation`)는 `Job` 테이블을 쓰지
  않지만(`docs/erd/entities.md` 3.3, 3.6), "시작 → id 발급 → 상태 조회" 패턴 자체는 이
  문서의 공통 규약을 그대로 따른다고 제안한다. 다만 평가가 폴링이 필요할 만큼 오래 걸리는
  작업인지조차 정해지지 않았다 — **작업별 이슈(prediction)에서 결정.**

### 2.5 오류 응답 규약

**없는 작업(404)과 일시 오류 구분 — 제안, 기존 코드와 일치**

- 모르는 `jobId`로 상태를 조회하면 ai는 HTTP `404`를 반환한다. `PythonAiClient`가 이를
  `AiJobNotFoundException`으로 변환하고, `JobService`가 `Job`을 `FAILED`로 기록한다
  (`docs/backend/job.md` 3.3).
- 그 외 오류(타임아웃, 5xx, 연결 실패 등)는 `404`가 아닌 다른 방식으로 구분되기만 하면 된다
  (지금 코드는 `AiJobNotFoundException`이 아닌 모든 예외를 "일시 오류"로 본다). backend는
  `Job` 상태를 유지하고 다음 폴링에서 다시 시도한다. ai가 "없는 작업"을 5xx로 잘못 응답하면
  멀쩡한 작업이 `FAILED`로 처리되므로, **404는 정확히 "모르는 jobId"일 때만 써야 한다.**
- 자료 준비/분석/예측 시작 호출이 실패하면(4xx/5xx 무엇이든) backend는 `markFailed`로 끝내고
  자동 재시도하지 않는다(`job.md` 3.3 "다른 도메인이 작업을 시작하는 순서"). 시작 실패를
  "일시 오류"로 구분해 재시도해주는 기능은 MVP에 없다.

**오류 메시지 범위 — 제안**

- `errorMessage`는 backend가 그대로 저장하고(`Job.errorMessage`), 그대로 클라이언트 응답에도
  노출한다(`job.md` 2.4, `GET /api/v1/jobs/{id}`의 `data.errorMessage`). 즉 ai가 보낸 문자열이
  **필터링 없이 외부에 보인다.**
- 그래서 ai의 `errorMessage`는 다음을 포함하지 않아야 한다: 스택트레이스, 내부 파일 경로,
  문서 원문/발췌, 모델명·프롬프트 같은 내부 구성 정보.
- 길이는 500자 이내를 제안한다(근거: `Job.errorMessage` 컬럼 길이에 맞춰 Flyway에서 정할 값과
  맞춰야 하므로, 구체 숫자는 마이그레이션 작성 시 한 번 더 맞춘다). **열림(정확한 글자 수).**

### 2.6 작업 상태 저장 위치 (재시작해도 유지되는지) — 열림, 임의로 정하지 않음

루트 `CLAUDE.md` 7번에 "아직 정해지지 않은 것 (임의로 정하지 말 것)"으로 명시된 항목이라
여기서 하나로 단정하지 않는다. 선택지를 정리한다.

| 방식 | 장점 | 단점 |
|---|---|---|
| A. ai도 영구 저장 (자체 DB/파일) | ai 재시작·재배포에도 진행 중 작업이 살아남는다 | ai 쪽에 저장소가 하나 더 생긴다(구현·운영 비용) |
| B. ai는 메모리만 유지 (`PythonAiClientStub`과 동일한 방식) | 구현이 단순하다 | ai가 재시작하면 모든 진행 중 작업의 `jobId`가 사라져 backend는 2.5의 "없는 작업"(404)으로 보고 `FAILED` 처리한다. 사용자가 재요청해야 한다 |

- backend 쪽은 어느 쪽이든 영향받지 않는다(`Job`은 이미 MySQL에 영구 저장되고, "ai에 없는
  작업"을 `FAILED`로 처리하는 경로가 있다). 그래서 이 선택은 **ai 담당자의 운영 편의와 재배포
  빈도에 달려 있다.**
- 제안하지는 않지만 참고: K8s 전환(#43) 이후 ai/파이프라인 쪽 파드가 아직 없어(`infra/k8s`에
  ai 매니페스트 없음) 재배포 빈도·재시작 패턴을 가늠하기 어렵다. 그 매니페스트가 나오는
  시점에 다시 논의하는 게 맞다.

### 2.7 결과 보관 기간, backend가 결과를 가져가는 시점의 책임 — 제안 + 열림

- backend는 결과 본문을 `Job` 테이블에 두지 않는다(`job.md` "작업 결과 본문은 담지 않는다").
  `Job`이 `SUCCEEDED`로 바뀔 때 호출한 `getJobStatus`의 `result`를 대상 도메인(예:
  `AnalysisService`)이 그 자리에서 읽어 자신의 테이블(`AnalysisFinding` 등)에 영구 저장하는
  모양을 전제로 한다.
- **문제**: `JobService.getJob`은 작업이 이미 종료 상태면 ai를 다시 호출하지 않는다
  (`job.md` 3.3 "종료됐거나 externalJobId가 없으면 그대로 반환"). 즉 `SUCCEEDED`로 전이되는
  **그 한 번의 호출에서** 대상 도메인이 결과 저장까지 성공해야 한다. 그 저장이 실패하면
  (DB 오류 등) 재시도할 방법이 지금 설계에는 없다. 이 흐름을 어떻게 안전하게 만들지는
  작업별 이슈(분석/예측)에서 정할 몫이지만, 공통 규약 차원에서 ai에게 다음을 요구한다.
- **요구(제안)**: ai는 `SUCCEEDED`/`FAILED`가 된 뒤에도 같은 `jobId`로 같은 `result`를
  최소 보관 기간 동안 **반복 조회 가능**해야 한다(멱등 조회). backend가 저장에 실패해 다음
  폴링에서 다시 같은 결과를 받아 재시도할 여지를 남기기 위함이다.
- 보관 기간의 정확한 값은 **열림**(제안: 24시간, ai 쪽 저장 비용과 함께 확정). 보관 기간이
  지난 뒤 조회하면 2.5의 "없는 작업"(404)과 같게 처리한다.

### 2.8 중복 요청 처리, 동시 실행 개수 — 제안

- **멱등성**: MVP에서 backend는 시작 요청(`startXxx`)이 실패해도 자동 재시도하지 않는다
  (`markFailed`로 끝남, `job.md` 3.3). 그래서 "같은 요청이 중복 전송되어도 한 번만 실행"을
  ai 쪽에 지금 당장 요구하지 않는다. 사용자가 같은 작업을 다시 요청하면 backend는 새
  `Job`을 만들어 새로 시작 요청을 보내고, ai는 이를 **의도된 새 작업**으로 처리한다.
- backend가 나중에 자동 재시도를 도입하면 멱등키(예: `Idempotency-Key` 헤더에 `Job.id` 전달)가
  필요해진다. 지금은 설계하지 않고 후속 과제로 남긴다(5번 후속 이슈 목록).
- **동시 실행 개수**: 재무 데이터 적재는 이미 "한 번에 한 건"으로 backend가 제한한다
  (`entities.md` 3.3 "이미 실행 중인 적재가 있으면 건너뛴다"). 자료 준비/분석/예측은
  사용자별로 독립적인 작업이라 backend는 동시 실행 수를 제한하지 않는다. ai 서비스 자체의
  동시 처리 한도(워커 수, 큐 길이 등)는 ai 담당 영역이며 backend가 알 필요는 없다 — 다만
  한도를 넘겨 받은 요청을 어떻게 응답할지는 **열림**(예: `429` vs 큐에 쌓아두고 `202`).

### 2.9 허용 문서·기준 시점을 backend가 확정해 전달하고 ai가 바꾸지 못하게 하는 방식 — 제안 + 발견한 간극

- 원칙은 이미 세워져 있다(`backend/CLAUDE.md` 6번, `entities.md` 5): 허용 문서 범위와
  기준 시점은 서버가 강제하고, LLM이 인자로 정하지 않는다. `AnalysisRequest`가 이미
  `allowedDocumentVersionIds`(목록)와 `asOfDate`를 갖고 있다.
- **간극**: `PredictionRequest`(현재 초안)에는 `analysisRunId`와 `asOfDate`만 있고
  `allowedDocumentVersionIds`가 없다. 그런데 `entities.md` 3.5는 "ai는 기준 시점 이전에
  발행된 자료만 사용한다. 이후 발행 문서는 서버가 허용 문서에서 제외한다"고 적어, 예측도
  명시적 허용 목록이 필요함을 전제로 한다(예측 시점과 분석 시점 사이에 새로 들어온 문서가
  있을 수 있다). `PythonAiClient`/`PredictionRequest`에 `allowedDocumentVersionIds` 추가가
  필요해 보인다 — **후속 이슈로 올림(5번)**, 확정은 prediction 작업별 이슈에서.
- **ai가 바꾸지 못하게 하는 방식(제안)**: 전달은 "강제"지만 확인은 "검증"으로 한다.
  1. backend가 허용 문서 목록을 계산해 요청 바디에 통째로 넘긴다(이미 그렇다).
  2. ai/`mcp-server`의 조회 도구는 이 목록으로만 검색 범위를 제한한다(LLM이 날짜·범위를
     스스로 고르게 하지 않는다).
  3. ai가 돌려준 결과(`evidenceRef` 등)가 허용 목록 밖의 문서를 가리키면, **backend가 저장
     시점에 걸러낸다.** 숫자는 코드로 계산하고 공시와 대조한다는 원칙(`CLAUDE.md` 4번)과
     같은 맥락이다 — ai의 응답이라도 그대로 믿지 않는다.
  - 걸러낸 뒤 분석 전체를 실패로 볼지, 그 항목만 버리고 나머지는 저장할지는 **열림**
    (제안: 그 항목만 버리고 경고 로그를 남긴다 — 전체 실패는 사용자 경험상 과하다).

### 2.10 호출 인증, 타임아웃 — 제안

- **인증**: backend ↔ ai는 같은 신뢰 경계 안의 내부 호출이다(외부에 노출되지 않음). 공유
  비밀키를 헤더로 보내는 방식을 제안한다: `X-Internal-Api-Key: {값}`. 값은 환경변수로 주입하고
  (`.env.example`에는 변수명만, 루트 `CLAUDE.md` 6번) backend·ai 양쪽에 같은 값을 둔다.
  변수명 제안: backend `AI_SERVICE_API_KEY`, ai `INTERNAL_API_KEY`(ai 쪽 명명은 ai 담당자
  재량). mTLS 등 더 강한 방식은 MVP 범위를 넘는다고 본다.
- **타임아웃(제안, 숫자는 조정 가능)**: 연결 타임아웃 2초(공통). 시작 호출(`startXxx`)은
  읽기 타임아웃 5초 — 작업을 큐에 올리는 것만 하므로 오래 걸리지 않아야 한다. 상태 조회는
  읽기 타임아웃 3초 — 폴링마다 호출되므로 더 짧게 잡는다. 분석·예측 자체의 실행 시간은
  비동기 처리로 가려지므로 이 타임아웃들과 무관하다.
- 타임아웃이 발생하면 2.5의 "일시 오류"로 처리한다(시작 호출은 `markFailed`, 상태 조회는
  상태 유지 후 재시도).

### 2.11 파일 접근 방식 (`fileStorageKey`, 공유 볼륨) — 제안 + 열림(infra 협의 필요)

- 지금은 단일 서버 구성이고 `docker-compose.yml`의 `./data` 볼륨을 backend 컨테이너가
  마운트한다. `FileStorage`(로컬 구현)가 반환하는 `fileStorageKey`는 이 볼륨 기준 상대
  경로다. ai 컨테이너도 같은 볼륨을 마운트하면 같은 키로 파일을 읽을 수 있다는 전제다.
- K8s 전환(#43)은 backend/frontend/mysql까지만 끝났고(`infra/k8s/*.yaml`), ai·pipelines용
  매니페스트는 아직 없다. 공유 볼륨 전제가 K8s에서도 그대로 성립하려면 backend와 ai 파드가
  같은 PVC를 마운트해야 하는데, 이건 **이 문서(backend ↔ ai 규약)만으로 결정할 수 없고
  infra 담당 영역과 함께 정해야 한다.** `CLAUDE.md` 2번 규칙에 따라 이 자리에서 infra
  파일을 직접 건드리지 않고, 후속 이슈로만 남긴다(5번).
- 이후 객체 스토리지(S3 등)로 전환하면 `fileStorageKey`는 버킷 키가 되고 ai가 자격증명으로
  직접 접근하는 모양으로 바뀐다. 전환 시점은 **열림**(문서 보관 정책 미정과도 연결 —
  루트 `CLAUDE.md` 7번).

### 2.12 파이프라인 호출 방식 (파이썬 함수 호출 여부) — 제안, ai 담당자 최종 합의 필요

- **제안: HTTP(FastAPI)**, 함수 직접 호출(JNI, 프로세스 내 Python 임베딩 등)은 채택하지 않는다.
- 이유:
  1. 언어가 다르다(backend는 Java/Spring, ai는 Python). 같은 프로세스에 묶으면 배포·장애
     격리가 모두 얽힌다.
  2. `PythonAiClient`는 이미 "구현체는 설정으로 교체"(`infra.python-ai-client.mode`,
     지금은 `stub`)하는 모양으로 설계돼 있다. 함수 호출은 이 교체 가능성(원격 HTTP 구현체로
     바꿔 끼우는 것)과 맞지 않는다.
  3. K8s 전환(#43)으로 backend/frontend/mysql은 이미 별도 파드가 됐다. ai도 같은 방향으로
     갈 것이 자연스럽고, 별도 파드라면 네트워크 호출(HTTP)만 가능하다.
- **열림**: ai 담당자가 다른 이유로 함수 호출이나 다른 프로토콜(gRPC 등)을 선호하면 이 제안을
  바꿀 수 있다. 이 문서의 나머지 항목(2.4, 2.9, 2.10)은 HTTP를 전제로 적었다.

## 3. 아직 정해지지 않은 것 (요약)

| 항목 | 관련 절 | 비고 |
|---|---|---|
| ai 서비스의 작업 상태 저장 위치 | 2.6 | 루트 `CLAUDE.md` 7번. 임의로 정하지 않음 |
| 엔드포인트 경로·라우팅 방식 | 2.4 | ai 담당자의 FastAPI 설계 선호에 맞춤 |
| `errorMessage` 최대 길이 | 2.5 | Flyway 작성 시 함께 확정 |
| 결과 보관 기간 정확한 값 | 2.7 | 제안 24시간 |
| ai 서비스 동시 처리 한도 초과 시 응답 | 2.8 | `429` vs 큐잉 |
| `PredictionRequest`에 `allowedDocumentVersionIds` 추가 여부 | 2.9 | prediction 작업별 이슈에서 최종 확정 |
| 허용 목록 밖 근거를 걸러낼 때 분석 전체 실패 여부 | 2.9 | 제안: 해당 항목만 버림 |
| 공유 볼륨(PVC)을 ai/pipelines K8s 매니페스트에 어떻게 구성할지 | 2.11 | infra 담당 영역과 협의 |
| 파이프라인 호출 방식(HTTP 최종 확정) | 2.12 | ai 담당자 합의 필요 |
| 재무 데이터 적재·예측 평가가 이 폴링 패턴을 그대로 쓸지 | 2.4 | 작업별 이슈(datasync, prediction)에서 결정 |

## 4. 후속 이슈 목록 (제안)

합의가 끝나면 아래 작업이 필요하다. 실제 이슈 등록은 사용자 요청 시에만 한다(루트 `CLAUDE.md` 5번).

| 영역 | 내용 |
|---|---|
| ai | FastAPI 껍데기 구현: 2.4의 엔드포인트, `X-Internal-Api-Key` 검증 미들웨어 |
| backend | `PythonAiClient` 실제 HTTP 구현체 추가 (`infra.python-ai-client.mode`에 값 추가) |
| backend | `PredictionRequest`에 `allowedDocumentVersionIds` 추가, `AnalysisRequest`의 `profileSnapshotJson`은 profile 패키지 확정 후 정리 |
| backend | 예측 평가, 재무 데이터 적재용 `PythonAiClient` 메서드 추가 (결과 보관·폴링 패턴 적용 여부는 각 작업별 이슈에서 먼저 확정) |
| infra | ai/pipelines K8s 매니페스트, 공유 볼륨(PVC) 구성 — backend/ai 양쪽과 협의 |
| backend/ai 공통 | `AI_SERVICE_API_KEY`/`INTERNAL_API_KEY` 환경변수를 `.env.example`에 추가 |

## 5. 다시 확인할 시점

| 시점 | 확인할 것 |
|---|---|
| ai 담당자 확인 후 | 이 문서의 "제안"·"열림" 표시를 실제 합의 결과로 갱신, 이슈 #47 체크리스트 반영 |
| ai/pipelines의 FastAPI 껍데기를 만들 때 | 2.4의 경로·응답 형식이 실제 구현과 일치하는지 |
| infra가 ai/pipelines K8s 매니페스트를 만들 때 | 2.11의 공유 볼륨 전제가 유지되는지 |
| prediction 작업별 이슈를 진행할 때 | 2.9의 `allowedDocumentVersionIds` 간극, 2.4의 평가 폴링 여부 |
| api-spec 문서가 만들어질 때 | 2.5의 에러 코드 접두사(`job.md` 미정 항목과 공유), 폴링 간격 권장값 |
